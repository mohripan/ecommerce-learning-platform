namespace NotificationService.Models;

public record OrderEvent(
    string OrderId,
    string CustomerEmail,
    string Status,
    string? TrackingNumber,
    string? CancellationReason,
    string? WebhookUrl);

public record InventoryLowEvent(
    string ProductId,
    string ProductName,
    int RemainingStock,
    string WarehouseManagerEmail);

public record EmailNotificationRequest(
    string To,
    string Subject,
    string Body);

public class KafkaSettings
{
    public string BootstrapServers { get; set; } = "localhost:9092";
    public string GroupId { get; set; } = "notification-service";
    public List<string> Topics { get; set; } = ["order-events", "inventory-events"];
    public string DeadLetterTopic { get; set; } = "notification-dlq";
}

public class SmtpSettings
{
    public string Host { get; set; } = "localhost";
    public int Port { get; set; } = 587;
    public string UserName { get; set; } = "";
    public string Password { get; set; } = "";
    public string FromAddress { get; set; } = "noreply@ecommerce.local";
    public string FromName { get; set; } = "Ecommerce Platform";
}

public class WebhookSettings
{
    public int MaxRetries { get; set; } = 3;
    public int TimeoutSeconds { get; set; } = 10;
}
