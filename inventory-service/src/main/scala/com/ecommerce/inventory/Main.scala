package com.ecommerce.inventory

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.stream.KillSwitches
import com.ecommerce.inventory.actor.InventoryManager
import com.ecommerce.inventory.api.InventoryRoutes
import com.ecommerce.inventory.kafka.{InventoryEventConsumer, InventoryEventProducer}
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * Application entry point.
 *
 * Start-up sequence:
 *   1. Initialise OpenTelemetry SDK (reads OTEL_* env vars).
 *   2. Create the [[ActorSystem]] with a cluster-enabled configuration.
 *   3. Bootstrap Akka Cluster Sharding via [[InventoryManager]].
 *   4. Start the Kafka consumer (reads order-events).
 *   5. Start the Akka HTTP server (serves the REST API).
 *   6. Register JVM shutdown hook for graceful termination.
 */
object Main:

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val otel: OpenTelemetry = buildOpenTelemetry()
    val tracer: Tracer      = otel.getTracer("inventory-service", "1.0.0")

    // Root behaviour — guardian just idles; real work happens in sharded entities.
    given system: ActorSystem[Nothing] =
      ActorSystem(Behaviors.empty[Nothing], "InventorySystem")

    given ec: ExecutionContext = system.executionContext

    log.info("Starting Inventory Service …")

    // ── 1. Cluster sharding ─────────────────────────────────────────────
    val manager = InventoryManager(system)
    log.info("Cluster sharding initialised")

    // ── 2. Kafka producer ────────────────────────────────────────────────
    val producer = InventoryEventProducer(tracer)

    // ── 3. Kafka consumer ────────────────────────────────────────────────
    val consumer        = InventoryEventConsumer(manager, producer, tracer)
    val consumerKillSwitch = consumer.start()
    log.info("Kafka consumer started")

    // ── 4. HTTP server ───────────────────────────────────────────────────
    val routes  = InventoryRoutes(manager, tracer)
    val cfg     = system.settings.config
    val host    = cfg.getString("inventory.http.host")
    val port    = cfg.getInt("inventory.http.port")

    Http()
      .newServerAt(host, port)
      .bind(routes.routes)
      .onComplete {
        case Success(binding) =>
          log.info("HTTP server listening on {}:{}", host, port)
          registerShutdownHook(binding, consumerKillSwitch, producer, otel, system)
        case Failure(ex) =>
          log.error("Failed to bind HTTP server: {}", ex.getMessage, ex)
          system.terminate()
      }

  // ── Graceful shutdown ──────────────────────────────────────────────────

  private def registerShutdownHook(
      binding: akka.http.scaladsl.Http.ServerBinding,
      consumerKillSwitch: akka.stream.UniqueKillSwitch,
      producer: InventoryEventProducer,
      otel: OpenTelemetry,
      system: ActorSystem[?],
  )(using ec: ExecutionContext): Unit =
    sys.addShutdownHook {
      log.info("Shutdown initiated …")

      // Stop accepting new HTTP connections.
      binding.terminate(hardDeadline = java.time.Duration.ofSeconds(10))

      // Drain and stop the Kafka consumer.
      consumerKillSwitch.shutdown()

      // Flush and close the Kafka producer.
      producer.close()

      // Flush OTEL spans.
      otel match
        case sdk: OpenTelemetrySdk => sdk.getSdkTracerProvider.shutdown()
        case _                     => ()

      // Terminate the actor system.
      system.terminate()
      log.info("Shutdown complete")
    }

  // ── OpenTelemetry initialisation ───────────────────────────────────────

  /**
   * Bootstraps the OpenTelemetry SDK using auto-configuration.
   * Configuration is driven by standard environment variables:
   *   OTEL_SERVICE_NAME, OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_TRACES_SAMPLER, …
   */
  private def buildOpenTelemetry(): OpenTelemetry =
    try
      AutoConfiguredOpenTelemetrySdk
        .builder()
        .setResultAsGlobal()
        .build()
        .getOpenTelemetrySdk
    catch
      case ex: Exception =>
        log.warn("OpenTelemetry auto-configuration failed ({}); falling back to no-op", ex.getMessage)
        OpenTelemetry.noop()
