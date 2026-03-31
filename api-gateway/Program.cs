using System.Text.Json;
using System.Threading.RateLimiting;
using ApiGateway.Middleware;
using ApiGateway.Services;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Microsoft.IdentityModel.Tokens;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

var builder = WebApplication.CreateBuilder(args);
var config = builder.Configuration;

// ---------------------------------------------------------------------------
// OpenTelemetry
// ---------------------------------------------------------------------------
var otelServiceName    = config["OpenTelemetry:ServiceName"]    ?? "api-gateway";
var otelServiceVersion = config["OpenTelemetry:ServiceVersion"] ?? "1.0.0";
var otlpEndpoint       = config["OpenTelemetry:OtlpEndpoint"]   ?? "http://localhost:4317";
var enableConsole      = config.GetValue<bool>("OpenTelemetry:EnableConsoleExporter");

builder.Services.AddOpenTelemetry()
    .ConfigureResource(r => r
        .AddService(otelServiceName, serviceVersion: otelServiceVersion)
        .AddAttributes(new Dictionary<string, object>
        {
            ["deployment.environment"] = builder.Environment.EnvironmentName.ToLowerInvariant()
        }))
    .WithTracing(tracing =>
    {
        tracing
            .AddAspNetCoreInstrumentation(o =>
            {
                o.RecordException = true;
                o.Filter = ctx =>
                    // Skip health check noise from tracing.
                    !ctx.Request.Path.StartsWithSegments("/health");
            })
            .AddHttpClientInstrumentation(o => o.RecordException = true)
            .AddOtlpExporter(o => o.Endpoint = new Uri(otlpEndpoint));

        if (enableConsole)
            tracing.AddConsoleExporter();
    });

// ---------------------------------------------------------------------------
// Authentication – JWT Bearer
// ---------------------------------------------------------------------------
builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.Authority             = config["Jwt:Authority"];
        options.Audience              = config["Jwt:Audience"];
        options.RequireHttpsMetadata  = config.GetValue<bool>("Jwt:RequireHttpsMetadata");

        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer           = config.GetValue<bool>("Jwt:ValidateIssuer"),
            ValidateAudience         = config.GetValue<bool>("Jwt:ValidateAudience"),
            ValidateLifetime         = config.GetValue<bool>("Jwt:ValidateLifetime"),
            ClockSkew                = TimeSpan.FromSeconds(
                                           config.GetValue<int>("Jwt:ClockSkewSeconds", 30)),
            ValidateIssuerSigningKey = true,
        };

        options.Events = new JwtBearerEvents
        {
            OnAuthenticationFailed = ctx =>
            {
                var log = ctx.HttpContext.RequestServices
                              .GetRequiredService<ILogger<Program>>();
                log.LogWarning("JWT authentication failed: {Error}", ctx.Exception.Message);
                return Task.CompletedTask;
            }
        };
    });

builder.Services.AddAuthorization();

// ---------------------------------------------------------------------------
// Rate limiting  (.NET 8 built-in)
// ---------------------------------------------------------------------------
builder.Services.AddRateLimiter(limiter =>
{
    // Reject immediately when the queue is full; return 429.
    limiter.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    limiter.OnRejected = async (ctx, token) =>
    {
        ctx.HttpContext.Response.Headers["Retry-After"] = "60";
        await ctx.HttpContext.Response.WriteAsJsonAsync(
            new { error = "Too many requests. Please retry after 60 seconds." },
            cancellationToken: token);
    };

    // --- Global sliding-window policy (applied to all endpoints) -----------
    limiter.AddSlidingWindowLimiter(
        policyName: RateLimitPolicies.Global,
        options =>
        {
            options.PermitLimit       = config.GetValue<int>("RateLimiting:GlobalPolicy:PermitLimit", 500);
            options.Window            = TimeSpan.FromSeconds(
                                            config.GetValue<int>("RateLimiting:GlobalPolicy:WindowSeconds", 60));
            options.SegmentsPerWindow = 6;
            options.QueueProcessingOrder = QueueProcessingOrder.OldestFirst;
            options.QueueLimit        = config.GetValue<int>("RateLimiting:GlobalPolicy:QueueLimit", 50);
        });

    // --- Per-authenticated-user fixed-window policy  -----------------------
    limiter.AddPolicy(
        policyName: RateLimitPolicies.Authenticated,
        partitioner: ctx =>
        {
            // Key on the subject claim so each user has their own bucket.
            var userId = ctx.User.FindFirst("sub")?.Value
                      ?? ctx.User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)?.Value
                      ?? "anonymous";

            return RateLimitPartition.GetFixedWindowLimiter(
                partitionKey: userId,
                factory: _ => new FixedWindowRateLimiterOptions
                {
                    PermitLimit      = config.GetValue<int>("RateLimiting:AuthenticatedPolicy:PermitLimit", 1000),
                    Window           = TimeSpan.FromSeconds(
                                           config.GetValue<int>("RateLimiting:AuthenticatedPolicy:WindowSeconds", 60)),
                    QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                    QueueLimit       = config.GetValue<int>("RateLimiting:AuthenticatedPolicy:QueueLimit", 100),
                });
        });

    // --- Orders endpoint fixed-window policy  ------------------------------
    limiter.AddFixedWindowLimiter(
        policyName: RateLimitPolicies.Orders,
        options =>
        {
            options.PermitLimit      = config.GetValue<int>("RateLimiting:OrdersPolicy:PermitLimit", 200);
            options.Window           = TimeSpan.FromSeconds(
                                           config.GetValue<int>("RateLimiting:OrdersPolicy:WindowSeconds", 60));
            options.QueueProcessingOrder = QueueProcessingOrder.OldestFirst;
            options.QueueLimit       = config.GetValue<int>("RateLimiting:OrdersPolicy:QueueLimit", 20);
        });

    // --- Inventory endpoint fixed-window policy  ---------------------------
    limiter.AddFixedWindowLimiter(
        policyName: RateLimitPolicies.Inventory,
        options =>
        {
            options.PermitLimit      = config.GetValue<int>("RateLimiting:InventoryPolicy:PermitLimit", 300);
            options.Window           = TimeSpan.FromSeconds(
                                           config.GetValue<int>("RateLimiting:InventoryPolicy:WindowSeconds", 60));
            options.QueueProcessingOrder = QueueProcessingOrder.OldestFirst;
            options.QueueLimit       = config.GetValue<int>("RateLimiting:InventoryPolicy:QueueLimit", 30);
        });
});

// ---------------------------------------------------------------------------
// Downstream HTTP clients with resiliency pipelines
// ---------------------------------------------------------------------------
static Action<IServiceProvider, HttpClient> ConfigureClient(IConfiguration cfg, string key) =>
    (_, client) =>
    {
        var baseUrl = cfg[$"DownstreamServices:{key}:BaseUrl"]
                      ?? throw new InvalidOperationException($"DownstreamServices:{key}:BaseUrl is not configured.");
        client.BaseAddress = new Uri(baseUrl.TrimEnd('/') + "/");
        client.DefaultRequestHeaders.Add("Accept", "application/json");
        client.Timeout = TimeSpan.FromSeconds(
            cfg.GetValue<int>($"DownstreamServices:{key}:TimeoutSeconds", 30));
    };

builder.Services
    .AddHttpClient<OrderServiceProxy>(ConfigureClient(config, "OrderService"))
    .AddStandardResilienceHandler(o =>
    {
        o.Retry.MaxRetryAttempts = config.GetValue<int>(
            "DownstreamServices:OrderService:RetryCount", 3);
    });

builder.Services
    .AddHttpClient<InventoryServiceProxy>(ConfigureClient(config, "InventoryService"))
    .AddStandardResilienceHandler(o =>
    {
        o.Retry.MaxRetryAttempts = config.GetValue<int>(
            "DownstreamServices:InventoryService:RetryCount", 3);
    });

// ---------------------------------------------------------------------------
// Health checks
// ---------------------------------------------------------------------------
builder.Services
    .AddHealthChecks()
    .AddUrlGroup(
        uri: new Uri(config["HealthChecks:OrderServiceUrl"]
                     ?? "http://order-service:8080/health"),
        name: "order-service",
        failureStatus: HealthStatus.Degraded,
        tags: ["downstream", "orders"])
    .AddUrlGroup(
        uri: new Uri(config["HealthChecks:InventoryServiceUrl"]
                     ?? "http://inventory-service:8080/health"),
        name: "inventory-service",
        failureStatus: HealthStatus.Degraded,
        tags: ["downstream", "inventory"]);

// ---------------------------------------------------------------------------
// Misc services
// ---------------------------------------------------------------------------
builder.Services.AddProblemDetails();

// ---------------------------------------------------------------------------
// Build the application
// ---------------------------------------------------------------------------
var app = builder.Build();

// ---------------------------------------------------------------------------
// Middleware pipeline
// ---------------------------------------------------------------------------
app.UseExceptionHandler(errApp =>
{
    errApp.Run(async ctx =>
    {
        ctx.Response.StatusCode  = StatusCodes.Status500InternalServerError;
        ctx.Response.ContentType = "application/json";
        await ctx.Response.WriteAsJsonAsync(new
        {
            error = "An unexpected error occurred. Please try again later."
        });
    });
});

app.UseMiddleware<CorrelationIdMiddleware>();

app.UseAuthentication();
app.UseAuthorization();

// Rate limiting must come after auth so per-user policies can read the claims.
app.UseRateLimiter();

// ---------------------------------------------------------------------------
// Health check endpoints
// ---------------------------------------------------------------------------
app.MapHealthChecks("/health", new HealthCheckOptions
{
    ResponseWriter = HealthCheckResponseWriter.WriteDetailedJson
}).AllowAnonymous();

app.MapHealthChecks("/health/live", new HealthCheckOptions
{
    // Liveness: the process is alive – no downstream checks.
    Predicate = _ => false,
    ResponseWriter = HealthCheckResponseWriter.WriteDetailedJson
}).AllowAnonymous();

app.MapHealthChecks("/health/ready", new HealthCheckOptions
{
    // Readiness: all downstream dependencies must be healthy.
    Predicate = check => check.Tags.Contains("downstream"),
    ResponseWriter = HealthCheckResponseWriter.WriteDetailedJson
}).AllowAnonymous();

// ---------------------------------------------------------------------------
// Order Service routes  →  /api/v1/orders/**
// ---------------------------------------------------------------------------
var ordersGroup = app.MapGroup("/api/v1/orders")
    .RequireAuthorization()
    .RequireRateLimiting(RateLimitPolicies.Orders);

ordersGroup.MapGet("/", async (HttpContext ctx, OrderServiceProxy proxy, CancellationToken ct) =>
{
    var result = await proxy.GetOrdersAsync(ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

ordersGroup.MapGet("/{orderId}", async (
    string orderId,
    HttpContext ctx,
    OrderServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.GetOrderByIdAsync(orderId, ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

ordersGroup.MapPost("/", async (HttpContext ctx, OrderServiceProxy proxy, CancellationToken ct) =>
{
    var result = await proxy.CreateOrderAsync(ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

ordersGroup.MapPut("/{orderId}", async (
    string orderId,
    HttpContext ctx,
    OrderServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.UpdateOrderAsync(orderId, ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

ordersGroup.MapPost("/{orderId}/cancel", async (
    string orderId,
    HttpContext ctx,
    OrderServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.CancelOrderAsync(orderId, ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

ordersGroup.MapGet("/{orderId}/status", async (
    string orderId,
    HttpContext ctx,
    OrderServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.GetOrderStatusAsync(orderId, ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

// ---------------------------------------------------------------------------
// Inventory Service routes  →  /api/v1/inventory/**
// ---------------------------------------------------------------------------
var inventoryGroup = app.MapGroup("/api/v1/inventory")
    .RequireAuthorization()
    .RequireRateLimiting(RateLimitPolicies.Inventory);

inventoryGroup.MapGet("/", async (
    HttpContext ctx,
    InventoryServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.GetInventoryItemsAsync(ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

inventoryGroup.MapGet("/{sku}", async (
    string sku,
    HttpContext ctx,
    InventoryServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.GetInventoryItemBySkuAsync(sku, ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

inventoryGroup.MapGet("/{sku}/stock", async (
    string sku,
    HttpContext ctx,
    InventoryServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.CheckStockAsync(sku, ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

inventoryGroup.MapPost("/reserve", async (
    HttpContext ctx,
    InventoryServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.ReserveStockAsync(ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

inventoryGroup.MapPost("/release", async (
    HttpContext ctx,
    InventoryServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.ReleaseStockAsync(ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

inventoryGroup.MapPut("/{sku}", async (
    string sku,
    HttpContext ctx,
    InventoryServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.UpdateInventoryItemAsync(sku, ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

inventoryGroup.MapGet("/warehouses", async (
    HttpContext ctx,
    InventoryServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.GetWarehousesAsync(ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

inventoryGroup.MapGet("/warehouses/{warehouseId}/stock", async (
    string warehouseId,
    HttpContext ctx,
    InventoryServiceProxy proxy,
    CancellationToken ct) =>
{
    var result = await proxy.GetWarehouseStockAsync(warehouseId, ctx, ct);
    return Results.Content(System.Text.Encoding.UTF8.GetString(result.Body),
                           result.ContentType, statusCode: result.StatusCode);
});

// ---------------------------------------------------------------------------
// BFF Aggregation endpoint  →  /api/v1/aggregation/**
// ---------------------------------------------------------------------------
var aggregationGroup = app.MapGroup("/api/v1/aggregation")
    .RequireAuthorization()
    .RequireRateLimiting(RateLimitPolicies.Authenticated);

/// <summary>
/// Returns a combined view of order details and current stock levels for all
/// SKUs on that order.  Single network round-trip from the client's perspective.
/// </summary>
aggregationGroup.MapGet("/order-with-stock/{orderId}", async (
    string orderId,
    HttpContext ctx,
    OrderServiceProxy orderProxy,
    InventoryServiceProxy inventoryProxy,
    CancellationToken ct) =>
{
    // Fetch the order and kick off inventory lookups concurrently.
    var orderResult = await orderProxy.GetOrderByIdAsync(orderId, ctx, ct);

    if (!orderResult.IsSuccess)
        return Results.Content(System.Text.Encoding.UTF8.GetString(orderResult.Body),
                               orderResult.ContentType, statusCode: orderResult.StatusCode);

    // Parse the order body once; eagerly materialise the SKU list so the
    // document can stay open for the final response serialisation.
    JsonDocument? orderDoc = null;
    List<string> skus = [];

    try
    {
        orderDoc = JsonDocument.Parse(orderResult.Body);
        skus = ExtractSkusFromOrder(orderDoc.RootElement).ToList();
    }
    catch (JsonException)
    {
        // Order body is not JSON – return order data without stock levels.
    }

    var stockLevels = await inventoryProxy.GetAggregatedStockAsync(skus, ctx, ct);

    if (orderDoc is null)
    {
        // Non-JSON order body: return raw with empty stock map.
        return Results.Content(System.Text.Encoding.UTF8.GetString(orderResult.Body),
                               orderResult.ContentType, statusCode: orderResult.StatusCode);
    }

    using (orderDoc)
    {
        var response = new
        {
            order = orderDoc.RootElement,
            stock = stockLevels
        };

        return Results.Ok(response);
    }
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static IEnumerable<string> ExtractSkusFromOrder(JsonElement order)
{
    // Handles common shapes: order.items[].sku or order.lineItems[].sku
    foreach (var prop in new[] { "items", "lineItems", "orderItems" })
    {
        if (order.TryGetProperty(prop, out var items) &&
            items.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in items.EnumerateArray())
            {
                if (item.TryGetProperty("sku", out var sku) &&
                    sku.ValueKind == JsonValueKind.String)
                {
                    var s = sku.GetString();
                    if (!string.IsNullOrWhiteSpace(s))
                        yield return s;
                }
            }
        }
    }
}

app.Run();

// Expose Program for integration testing.
public partial class Program { }

// ---------------------------------------------------------------------------
// Rate limit policy name constants (shared with route registrations above)
// ---------------------------------------------------------------------------
static class RateLimitPolicies
{
    public const string Global        = "global";
    public const string Authenticated = "authenticated";
    public const string Orders        = "orders";
    public const string Inventory     = "inventory";
}

// ---------------------------------------------------------------------------
// Custom health-check response writer
// ---------------------------------------------------------------------------
static class HealthCheckResponseWriter
{
    private static readonly JsonSerializerOptions _jsonOpts = new()
    {
        WriteIndented = true
    };

    public static async Task WriteDetailedJson(HttpContext ctx, HealthReport report)
    {
        ctx.Response.ContentType = "application/json";

        var response = new
        {
            status          = report.Status.ToString(),
            totalDurationMs = report.TotalDuration.TotalMilliseconds,
            entries         = report.Entries.ToDictionary(
                kvp => kvp.Key,
                kvp => new
                {
                    status      = kvp.Value.Status.ToString(),
                    durationMs  = kvp.Value.Duration.TotalMilliseconds,
                    description = kvp.Value.Description,
                    error       = kvp.Value.Exception?.Message,
                    tags        = kvp.Value.Tags
                })
        };

        await ctx.Response.WriteAsync(
            JsonSerializer.Serialize(response, _jsonOpts));
    }
}
