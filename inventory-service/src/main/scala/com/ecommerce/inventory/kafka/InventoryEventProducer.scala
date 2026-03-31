package com.ecommerce.inventory.kafka

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.SendProducer
import com.ecommerce.inventory.model.*
import com.ecommerce.inventory.model.JsonProtocol.given
import io.opentelemetry.api.trace.{SpanKind, Tracer}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.{StringSerializer, ByteArraySerializer}
import org.slf4j.LoggerFactory
import spray.json.*

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/**
 * Alpakka Kafka producer that publishes inventory domain events to the
 * `inventory-events` topic so downstream services (e.g. the Order Saga)
 * can react to reservation outcomes.
 *
 * Message envelope format (JSON, UTF-8):
 * {{{
 *   {
 *     "eventType": "INVENTORY_RESERVED" | "INVENTORY_RESERVATION_FAILED",
 *     "inventoryId": "...",
 *     "orderId":  "...",
 *     "reason":   "..."   // only for FAILED
 *     "timestamp": "2024-01-01T00:00:00Z"
 *   }
 * }}}
 *
 * The message key is the `orderId` so that all events for a given order
 * land in the same Kafka partition (ordering guarantee).
 */
final class InventoryEventProducer(tracer: Tracer)(using system: ActorSystem[?]):

  private val log                    = LoggerFactory.getLogger(getClass)
  private given ec: ExecutionContext = system.executionContext

  private val config         = system.settings.config
  private val topic          = config.getString("inventory.kafka.inventory-events-topic")
  private val producerConfig = config.getConfig("akka.kafka.producer")

  private val producerSettings: ProducerSettings[String, Array[Byte]] =
    ProducerSettings(producerConfig, new StringSerializer, new ByteArraySerializer)
      .withBootstrapServers(config.getString("inventory.kafka.bootstrap-servers"))

  private val sendProducer: SendProducer[String, Array[Byte]] =
    SendProducer(producerSettings)

  /** Publish an INVENTORY_RESERVED event. */
  def publishReserved(inventoryId: String, orderId: String): Future[Unit] =
    val payload = InventoryReservedPayload(
      eventType   = "INVENTORY_RESERVED",
      inventoryId = inventoryId,
      orderId     = orderId,
      timestamp   = Instant.now().toString,
    )
    publish(orderId, payload.toJson.compactPrint)
      .map { meta =>
        log.info(
          "Published INVENTORY_RESERVED orderId={} partition={} offset={}",
          orderId,
          meta.partition,
          meta.offset,
        )
      }

  /** Publish an INVENTORY_RESERVATION_FAILED event. */
  def publishReservationFailed(
      inventoryId: String,
      orderId: String,
      reason: String,
  ): Future[Unit] =
    val payload = InventoryReservationFailedPayload(
      eventType   = "INVENTORY_RESERVATION_FAILED",
      inventoryId = inventoryId,
      orderId     = orderId,
      reason      = reason,
      timestamp   = Instant.now().toString,
    )
    publish(orderId, payload.toJson.compactPrint)
      .map { meta =>
        log.warn(
          "Published INVENTORY_RESERVATION_FAILED orderId={} reason={} partition={} offset={}",
          orderId,
          reason,
          meta.partition,
          meta.offset,
        )
      }

  /** Gracefully close the underlying Kafka producer. */
  def close(): Future[Done] = sendProducer.close()

  // ── Internal publish ───────────────────────────────────────────────────

  private def publish(key: String, jsonValue: String): Future[RecordMetadata] =
    val span = tracer
      .spanBuilder("kafka.produce inventory-events")
      .setSpanKind(SpanKind.PRODUCER)
      .setAttribute("messaging.system", "kafka")
      .setAttribute("messaging.destination", topic)
      .setAttribute("messaging.kafka.message_key", key)
      .startSpan()
    val scope = span.makeCurrent()

    val record: ProducerRecord[String, Array[Byte]] =
      ProducerRecord(topic, key, jsonValue.getBytes("UTF-8"))

    sendProducer
      .send(record)
      .andThen { _ =>
        scope.close()
        span.end()
      }
