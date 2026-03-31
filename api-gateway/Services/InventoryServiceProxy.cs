using System.Net;
using System.Text.Json;
using ApiGateway.Middleware;

namespace ApiGateway.Services;

/// <summary>
/// Typed HTTP proxy that forwards requests to the Inventory Service.
/// Mirrors the pattern established in <see cref="OrderServiceProxy"/>:
/// headers are propagated, the bearer token is forwarded, and the raw
/// response bytes are surfaced to the caller via a <see cref="ProxyResult"/>.
/// </summary>
public sealed class InventoryServiceProxy
{
    private readonly HttpClient _httpClient;
    private readonly ILogger<InventoryServiceProxy> _logger;

    public InventoryServiceProxy(HttpClient httpClient, ILogger<InventoryServiceProxy> logger)
    {
        _httpClient = httpClient;
        _logger = logger;
    }

    // -------------------------------------------------------------------------
    // Inventory items
    // -------------------------------------------------------------------------

    public async Task<ProxyResult> GetInventoryItemsAsync(
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = BuildPath("/inventory", context.Request.QueryString.Value);
        return await ForwardAsync(HttpMethod.Get, path, context, body: null, ct);
    }

    public async Task<ProxyResult> GetInventoryItemBySkuAsync(
        string sku,
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = $"/inventory/{Uri.EscapeDataString(sku)}";
        return await ForwardAsync(HttpMethod.Get, path, context, body: null, ct);
    }

    public async Task<ProxyResult> CheckStockAsync(
        string sku,
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = BuildPath($"/inventory/{Uri.EscapeDataString(sku)}/stock",
                             context.Request.QueryString.Value);
        return await ForwardAsync(HttpMethod.Get, path, context, body: null, ct);
    }

    public async Task<ProxyResult> ReserveStockAsync(
        HttpContext context,
        CancellationToken ct = default)
    {
        using var body = await ReadBodyAsync(context, ct);
        return await ForwardAsync(HttpMethod.Post, "/inventory/reserve", context, body, ct);
    }

    public async Task<ProxyResult> ReleaseStockAsync(
        HttpContext context,
        CancellationToken ct = default)
    {
        using var body = await ReadBodyAsync(context, ct);
        return await ForwardAsync(HttpMethod.Post, "/inventory/release", context, body, ct);
    }

    public async Task<ProxyResult> UpdateInventoryItemAsync(
        string sku,
        HttpContext context,
        CancellationToken ct = default)
    {
        using var body = await ReadBodyAsync(context, ct);
        var path = $"/inventory/{Uri.EscapeDataString(sku)}";
        return await ForwardAsync(HttpMethod.Put, path, context, body, ct);
    }

    // -------------------------------------------------------------------------
    // Warehouses
    // -------------------------------------------------------------------------

    public async Task<ProxyResult> GetWarehousesAsync(
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = BuildPath("/inventory/warehouses", context.Request.QueryString.Value);
        return await ForwardAsync(HttpMethod.Get, path, context, body: null, ct);
    }

    public async Task<ProxyResult> GetWarehouseStockAsync(
        string warehouseId,
        HttpContext context,
        CancellationToken ct = default)
    {
        var path = BuildPath(
            $"/inventory/warehouses/{Uri.EscapeDataString(warehouseId)}/stock",
            context.Request.QueryString.Value);
        return await ForwardAsync(HttpMethod.Get, path, context, body: null, ct);
    }

    // -------------------------------------------------------------------------
    // Aggregated view (order + inventory levels for a set of SKUs)
    // -------------------------------------------------------------------------

    /// <summary>
    /// Fetches stock levels for multiple SKUs in parallel and returns an
    /// aggregated response. This is the BFF's response-aggregation entry point.
    /// </summary>
    public async Task<Dictionary<string, object?>> GetAggregatedStockAsync(
        IEnumerable<string> skus,
        HttpContext context,
        CancellationToken ct = default)
    {
        var tasks = skus.Distinct(StringComparer.OrdinalIgnoreCase).Select(async sku =>
        {
            var result = await CheckStockAsync(sku, context, ct);
            object? parsed = null;

            if (result.IsSuccess && result.Body.Length > 0)
            {
                try
                {
                    parsed = JsonSerializer.Deserialize<JsonElement>(result.Body);
                }
                catch (JsonException)
                {
                    // Non-JSON body — surface the raw status instead.
                    parsed = new { status = result.StatusCode };
                }
            }
            else
            {
                parsed = new { status = result.StatusCode };
            }

            return (sku, parsed);
        });

        var results = await Task.WhenAll(tasks);
        return results.ToDictionary(r => r.sku, r => (object?)r.parsed);
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

        _logger.LogDebug("Forwarding {Method} {Path} to Inventory Service", method, path);

        try
        {
            using var response = await _httpClient.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, ct);

            return await ProxyResult.FromResponseAsync(response, ct);
        }
        catch (HttpRequestException ex)
        {
            _logger.LogError(ex, "Inventory Service request failed: {Method} {Path}", method, path);
            return ProxyResult.ServiceUnavailable("Inventory Service is currently unavailable.");
        }
        catch (TaskCanceledException ex) when (!ct.IsCancellationRequested)
        {
            _logger.LogError(ex, "Inventory Service request timed out: {Method} {Path}", method, path);
            return ProxyResult.GatewayTimeout("Inventory Service did not respond in time.");
        }
    }

    private static void PropagateHeaders(HttpContext context, HttpRequestMessage request)
    {
        if (context.Items.TryGetValue(CorrelationIdMiddleware.HeaderName, out var correlationId)
            && correlationId is string id)
        {
            request.Headers.TryAddWithoutValidation(CorrelationIdMiddleware.HeaderName, id);
        }

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
