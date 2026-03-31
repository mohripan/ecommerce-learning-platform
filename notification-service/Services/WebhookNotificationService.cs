using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;
using NotificationService.Models;

namespace NotificationService.Services;

public sealed class WebhookNotificationService
{
    private static readonly ActivitySource ActivitySource = new("NotificationService.Webhooks");

    private readonly HttpClient _httpClient;
    private readonly WebhookSettings _settings;
    private readonly ILogger<WebhookNotificationService> _logger;

    public WebhookNotificationService(
        HttpClient httpClient,
        IOptions<WebhookSettings> settings,
        ILogger<WebhookNotificationService> logger)
    {
        _httpClient = httpClient;
        _httpClient.Timeout = TimeSpan.FromSeconds(settings.Value.TimeoutSeconds);
        _settings = settings.Value;
        _logger = logger;
    }

    public async Task DispatchAsync(string url, object payload, CancellationToken ct)
    {
        using var activity = ActivitySource.StartActivity("WebhookDispatch", ActivityKind.Client);
        activity?.SetTag("webhook.url", url);
        activity?.SetTag("webhook.max_retries", _settings.MaxRetries);

        var json = JsonSerializer.Serialize(payload);
        var attempt = 0;

        while (attempt < _settings.MaxRetries)
        {
            attempt++;
            activity?.SetTag("webhook.attempt", attempt);

            try
            {
                using var content = new StringContent(json, Encoding.UTF8, "application/json");
                using var response = await _httpClient.PostAsync(url, content, ct);

                if (response.IsSuccessStatusCode)
                {
                    _logger.LogInformation(
                        "Webhook dispatched to {Url} on attempt {Attempt} — status {StatusCode}",
                        url, attempt, (int)response.StatusCode);
                    activity?.SetTag("webhook.status_code", (int)response.StatusCode);
                    return;
                }

                _logger.LogWarning(
                    "Webhook to {Url} returned {StatusCode} on attempt {Attempt}/{MaxRetries}",
                    url, (int)response.StatusCode, attempt, _settings.MaxRetries);
            }
            catch (TaskCanceledException) when (!ct.IsCancellationRequested)
            {
                _logger.LogWarning(
                    "Webhook to {Url} timed out on attempt {Attempt}/{MaxRetries}",
                    url, attempt, _settings.MaxRetries);
            }
            catch (HttpRequestException ex)
            {
                _logger.LogWarning(ex,
                    "Webhook to {Url} failed on attempt {Attempt}/{MaxRetries}",
                    url, attempt, _settings.MaxRetries);
            }
        }

        _logger.LogError("Webhook to {Url} failed after {MaxRetries} attempts", url, _settings.MaxRetries);
        activity?.SetStatus(ActivityStatusCode.Error, "All retry attempts exhausted");
    }
}
