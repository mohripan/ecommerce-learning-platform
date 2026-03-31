using NotificationService.Models;
using NotificationService.Services;

namespace NotificationService.Handlers;

public sealed class InventoryEventHandler
{
    private readonly EmailNotificationService _email;
    private readonly ILogger<InventoryEventHandler> _logger;

    public InventoryEventHandler(EmailNotificationService email, ILogger<InventoryEventHandler> logger)
    {
        _email = email;
        _logger = logger;
    }

    public async Task HandleAsync(InventoryLowEvent evt, CancellationToken ct)
    {
        _logger.LogWarning("Low inventory alert for product {ProductId}: {Remaining} remaining",
            evt.ProductId, evt.RemainingStock);

        var notification = new EmailNotificationRequest(
            To: evt.WarehouseManagerEmail,
            Subject: $"Low stock alert: {evt.ProductName}",
            Body: $"Product '{evt.ProductName}' (ID: {evt.ProductId}) has only {evt.RemainingStock} units remaining. Please restock.");

        await _email.SendAsync(notification, ct);
    }
}
