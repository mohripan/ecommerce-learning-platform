using NotificationService.Handlers;
using NotificationService.Services;
using NotificationService.Workers;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

var builder = Host.CreateApplicationBuilder(args);

builder.Services.Configure<KafkaSettings>(builder.Configuration.GetSection("Kafka"));
builder.Services.Configure<SmtpSettings>(builder.Configuration.GetSection("Smtp"));
builder.Services.Configure<WebhookSettings>(builder.Configuration.GetSection("Webhooks"));

builder.Services.AddHttpClient<WebhookNotificationService>()
    .AddStandardResilienceHandler();

builder.Services.AddSingleton<EmailNotificationService>();
builder.Services.AddSingleton<WebhookNotificationService>();
builder.Services.AddSingleton<OrderEventHandler>();
builder.Services.AddSingleton<InventoryEventHandler>();

builder.Services.AddOpenTelemetry()
    .ConfigureResource(r => r.AddService("notification-service"))
    .WithTracing(t => t
        .AddHttpClientInstrumentation()
        .AddOtlpExporter(o =>
        {
            o.Endpoint = new Uri(builder.Configuration["Otel:Endpoint"] ?? "http://localhost:4317");
        }));

builder.Services.AddHostedService<KafkaConsumerWorker>();

var host = builder.Build();
host.Run();
