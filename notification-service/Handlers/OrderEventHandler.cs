using NotificationService.Models;
using NotificationService.Services;

namespace NotificationService.Handlers;

public sealed class OrderEventHandler
{
    private readonly EmailNotificationService _email;
    private readonly WebhookNotificationService _webhook;
    private readonly ILogger<OrderEventHandler> _logger;

    public OrderEventHandler(
        EmailNotificationService email,
        WebhookNotificationService webhook,
        ILogger<OrderEventHandler> logger)
    {
        _email = email;
        _webhook = webhook;
        _logger = logger;
    }

    public async Task HandleAsync(string eventType, OrderEvent orderEvent, CancellationToken ct)
    {
        _logger.LogInformation("Handling {EventType} for order {OrderId}", eventType, orderEvent.OrderId);

        var (subject, body) = eventType switch
        {
            "OrderCreated"   => ("Your order has been placed",   $"Order {orderEvent.OrderId} has been received and is being processed."),
            "OrderConfirmed" => ("Your order is confirmed",      $"Order {orderEvent.OrderId} is confirmed and will be prepared for shipping."),
            "OrderShipped"   => ("Your order has shipped",       $"Order {orderEvent.OrderId} is on its way! Tracking: {orderEvent.TrackingNumber}"),
            "OrderCancelled" => ("Your order has been cancelled", $"Order {orderEvent.OrderId} was cancelled. Reason: {orderEvent.CancellationReason}"),
            _                => ("Order update",                  $"Update on order {orderEvent.OrderId}: {eventType}")
        };

        var notification = new EmailNotificationRequest(
            To: orderEvent.CustomerEmail,
            Subject: subject,
            Body: body);

        await _email.SendAsync(notification, ct);

        if (!string.IsNullOrEmpty(orderEvent.WebhookUrl))
        {
            await _webhook.DispatchAsync(orderEvent.WebhookUrl,
                new { eventType, orderEvent.OrderId, orderEvent.Status }, ct);
        }
    }
}
