using System.Net;
using System.Text.Json;
using ApiGateway.Middleware;

namespace ApiGateway.Services;

/// <summary>
/// Typed HTTP proxy that forwards requests to the Order Service and returns
/// the raw response stream to the caller. All forwarding preserves the
/// original query string, correlation-ID header and (when present) the
/// bearer token so that the downstream service can perform its own
/// authorisation checks.
/// </summary>
public sealed class OrderServiceProxy
{
    private readonly HttpClient _httpClient;
    private readonly ILogger<OrderServiceProxy> _logger;

    public OrderServiceProxy(HttpClient httpClient, ILogger<OrderServiceProxy> logger)
    {
        _httpClient = httpClient;
        _logger = logger;
    }

    // -------------------------------------------------------------------------
    // Orders
    // -------------------------------------------------------------------------

    public async Task<ProxyResult> GetOrdersAsync(
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = BuildPath("/orders", context.Request.QueryString.Value);
        return await ForwardAsync(HttpMethod.Get, path, context, body: null, ct);
    }

    public async Task<ProxyResult> GetOrderByIdAsync(
        string orderId,
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = BuildPath($"/orders/{Uri.EscapeDataString(orderId)}",
                             context.Request.QueryString.Value);
        return await ForwardAsync(HttpMethod.Get, path, context, body: null, ct);
    }

    public async Task<ProxyResult> CreateOrderAsync(
        HttpContext context,
        CancellationToken ct = default)
    {
        using var body = await ReadBodyAsync(context, ct);
        return await ForwardAsync(HttpMethod.Post, "/orders", context, body, ct);
    }

    public async Task<ProxyResult> UpdateOrderAsync(
        string orderId,
        HttpContext context,
        CancellationToken ct = default)
    {
        using var body = await ReadBodyAsync(context, ct);
        var path = $"/orders/{Uri.EscapeDataString(orderId)}";
        return await ForwardAsync(HttpMethod.Put, path, context, body, ct);
    }

    public async Task<ProxyResult> CancelOrderAsync(
        string orderId,
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = $"/orders/{Uri.EscapeDataString(orderId)}/cancel";
        return await ForwardAsync(HttpMethod.Post, path, context, body: null, ct);
    }

    public async Task<ProxyResult> GetOrderStatusAsync(
        string orderId,
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = $"/orders/{Uri.EscapeDataString(orderId)}/status";
        return await ForwardAsync(HttpMethod.Get, path, context, body: null, ct);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private async Task<ProxyResult> ForwardAsync(
        HttpMethod method,
        string path,
        HttpContext context,
        HttpContent? body,
        CancellationToken ct)
    {
        using var request = new HttpRequestMessage(method, path);

        if (body is not null)
            request.Content = body;

        PropagateHeaders(context, request);

        _logger.LogDebug("Forwarding {Method} {Path} to Order Service", method, Sanitize(path));

        try
        {
            using var response = await _httpClient.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, ct);

            return await ProxyResult.FromResponseAsync(response, ct);
        }
        catch (HttpRequestException ex)
        {
            _logger.LogError(ex, "Order Service request failed: {Method} {Path}", method, Sanitize(path));
            return ProxyResult.ServiceUnavailable("Order Service is currently unavailable.");
        }
        catch (TaskCanceledException ex) when (!ct.IsCancellationRequested)
        {
            _logger.LogError(ex, "Order Service request timed out: {Method} {Path}", method, Sanitize(path));
            return ProxyResult.GatewayTimeout("Order Service did not respond in time.");
        }
    }

    /// <summary>Strips CR/LF characters to prevent log-injection attacks.</summary>
    private static string Sanitize(string value) =>
        value.Replace("\r", string.Empty, StringComparison.Ordinal)
             .Replace("\n", string.Empty, StringComparison.Ordinal);

    private static void PropagateHeaders(HttpContext context, HttpRequestMessage request)
    {
        // Forward correlation ID.
        if (context.Items.TryGetValue(CorrelationIdMiddleware.HeaderName, out var correlationId)
            && correlationId is string id)
        {
            request.Headers.TryAddWithoutValidation(CorrelationIdMiddleware.HeaderName, id);
        }

        // Forward bearer token.
        if (context.Request.Headers.TryGetValue("Authorization", out var auth))
            request.Headers.TryAddWithoutValidation("Authorization", auth.ToString());
    }

    private static async Task<ByteArrayContent> ReadBodyAsync(HttpContext context, CancellationToken ct)
    {
        using var ms = new MemoryStream();
        await context.Request.Body.CopyToAsync(ms, ct);
        var content = new ByteArrayContent(ms.ToArray());

        if (context.Request.ContentType is { } contentType)
            content.Headers.TryAddWithoutValidation("Content-Type", contentType);

        return content;
    }

    private static string BuildPath(string basePath, string? queryString)
        => string.IsNullOrEmpty(queryString) ? basePath : $"{basePath}{queryString}";
}

// ---------------------------------------------------------------------------
// Shared result type
// ---------------------------------------------------------------------------

/// <summary>Value object that captures the downstream service response.</summary>
public sealed class ProxyResult
{
    public int StatusCode { get; init; }
    public byte[] Body { get; init; } = [];
    public string ContentType { get; init; } = "application/json";
    public bool IsSuccess => StatusCode is >= 200 and < 300;

    public static async Task<ProxyResult> FromResponseAsync(
        HttpResponseMessage response,
        CancellationToken ct)
    {
        var body = await response.Content.ReadAsByteArrayAsync(ct);
        var contentType = response.Content.Headers.ContentType?.ToString() ?? "application/json";

        return new ProxyResult
        {
            StatusCode  = (int)response.StatusCode,
            Body        = body,
            ContentType = contentType
        };
    }

    public static ProxyResult ServiceUnavailable(string message) =>
        ErrorResult((int)HttpStatusCode.ServiceUnavailable, message);

    public static ProxyResult GatewayTimeout(string message) =>
        ErrorResult((int)HttpStatusCode.GatewayTimeout, message);

    private static ProxyResult ErrorResult(int statusCode, string message) => new()
    {
        StatusCode  = statusCode,
        Body        = JsonSerializer.SerializeToUtf8Bytes(new { error = message }),
        ContentType = "application/json"
    };
}
