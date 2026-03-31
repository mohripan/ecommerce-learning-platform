using System.Text.Json;
using Confluent.Kafka;
using Microsoft.Extensions.Options;
using NotificationService.Handlers;
using NotificationService.Models;

namespace NotificationService.Workers;

public sealed class KafkaConsumerWorker : BackgroundService
{
    private readonly ILogger<KafkaConsumerWorker> _logger;
    private readonly KafkaSettings _settings;
    private readonly OrderEventHandler _orderHandler;
    private readonly InventoryEventHandler _inventoryHandler;

    public KafkaConsumerWorker(
        ILogger<KafkaConsumerWorker> logger,
        IOptions<KafkaSettings> settings,
        OrderEventHandler orderHandler,
        InventoryEventHandler inventoryHandler)
    {
        _logger = logger;
        _settings = settings.Value;
        _orderHandler = orderHandler;
        _inventoryHandler = inventoryHandler;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var config = new ConsumerConfig
        {
            BootstrapServers = _settings.BootstrapServers,
            GroupId = _settings.GroupId,
            AutoOffsetReset = AutoOffsetReset.Earliest,
            EnableAutoCommit = false
        };

        using var consumer = new ConsumerBuilder<string, string>(config).Build();
        consumer.Subscribe(_settings.Topics);

        _logger.LogInformation("Kafka consumer started. Topics: {Topics}", string.Join(", ", _settings.Topics));

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var result = consumer.Consume(stoppingToken);
                if (result?.Message is null) continue;

                await ProcessMessageAsync(result.Message, stoppingToken);
                consumer.Commit(result);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ConsumeException ex)
            {
                _logger.LogError(ex, "Kafka consume error: {Reason}", ex.Error.Reason);
                await SendToDeadLetterAsync(ex.ConsumerRecord?.Message, stoppingToken);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Unhandled error processing Kafka message");
            }
        }

        consumer.Close();
    }

    private async Task ProcessMessageAsync(Message<string, string> message, CancellationToken ct)
    {
        var eventType = message.Key;
        var payload = message.Value;

        _logger.LogInformation("Processing event {EventType}", eventType);

        switch (eventType)
        {
            case "OrderCreated":
            case "OrderConfirmed":
            case "OrderShipped":
            case "OrderCancelled":
                var orderEvent = JsonSerializer.Deserialize<OrderEvent>(payload);
                if (orderEvent is not null)
                    await _orderHandler.HandleAsync(eventType, orderEvent, ct);
                break;

            case "InventoryLow":
                var inventoryEvent = JsonSerializer.Deserialize<InventoryLowEvent>(payload);
                if (inventoryEvent is not null)
                    await _inventoryHandler.HandleAsync(inventoryEvent, ct);
                break;

            default:
                _logger.LogWarning("Unknown event type: {EventType}", eventType);
                break;
        }
    }

    private Task SendToDeadLetterAsync(Message<string, string>? message, CancellationToken ct)
    {
        if (message is null) return Task.CompletedTask;

        var dlqConfig = new ProducerConfig { BootstrapServers = _settings.BootstrapServers };
        using var producer = new ProducerBuilder<string, string>(dlqConfig).Build();
        return producer.ProduceAsync(_settings.DeadLetterTopic,
            new Message<string, string> { Key = message.Key, Value = message.Value }, ct);
    }
}
