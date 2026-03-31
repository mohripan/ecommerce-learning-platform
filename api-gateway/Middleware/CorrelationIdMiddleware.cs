namespace ApiGateway.Middleware;

/// <summary>
/// Ensures every request carries an X-Correlation-ID header so that all
/// downstream log entries and traces can be tied back to the originating call.
/// If the client supplies the header it is preserved; otherwise a new GUID is
/// generated. The chosen value is also written into the response so callers can
/// correlate client-side logs with server-side traces.
/// </summary>
public sealed class CorrelationIdMiddleware
{
    public const string HeaderName = "X-Correlation-ID";

    private readonly RequestDelegate _next;
    private readonly ILogger<CorrelationIdMiddleware> _logger;

    public CorrelationIdMiddleware(RequestDelegate next, ILogger<CorrelationIdMiddleware> logger)
    {
        _next = next;
        _logger = logger;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        var correlationId = GetOrCreateCorrelationId(context);

        // Make it available to downstream code via HttpContext.Items.
        context.Items[HeaderName] = correlationId;

        // Propagate to downstream HTTP clients via the ambient activity baggage
        // (picked up automatically by OpenTelemetry instrumentation).
        System.Diagnostics.Activity.Current?.SetBaggage(HeaderName, correlationId);

        // Echo the value back to the caller.
        context.Response.OnStarting(() =>
        {
            if (!context.Response.Headers.ContainsKey(HeaderName))
                context.Response.Headers[HeaderName] = correlationId;
            return Task.CompletedTask;
        });

        using (_logger.BeginScope(new Dictionary<string, object>
               {
                   [HeaderName] = correlationId
               }))
        {
            await _next(context);
        }
    }

    private static string GetOrCreateCorrelationId(HttpContext context)
    {
        if (context.Request.Headers.TryGetValue(HeaderName, out var existing)
            && !string.IsNullOrWhiteSpace(existing))
        {
            var value = existing.ToString().Trim();
            // Guard against absurdly long headers.
            return value.Length <= 128 ? value : Guid.NewGuid().ToString();
        }

        return Guid.NewGuid().ToString();
    }
}
