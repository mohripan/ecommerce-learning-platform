package com.ecommerce.inventory.kafka

import akka.actor.typed.ActorSystem
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.stream.scaladsl.Keep
import akka.stream.{KillSwitches, UniqueKillSwitch}
import com.ecommerce.inventory.actor.InventoryManager
import com.ecommerce.inventory.model.*
import com.ecommerce.inventory.model.JsonProtocol.given
import io.opentelemetry.api.trace.{SpanKind, Tracer}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, ByteArrayDeserializer}
import org.slf4j.LoggerFactory
import spray.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import akka.util.Timeout
import java.time.Instant

/**
 * Alpakka Kafka consumer that subscribes to the `order-events` topic.
 *
 * Uses `committableSource` for at-least-once delivery semantics: offsets are
 * committed to Kafka only after successful processing, so a restart will
 * re-process uncommitted messages rather than skip them.
 *
 * Event routing:
 *   ORDER_CREATED   → reserve inventory for each item; publish result to producer
 *   ORDER_CANCELLED → release all reservations for the order
 *   ORDER_CONFIRMED → confirm (finalise) all reservations for the order
 *
 * On partial reservation failure the consumer publishes
 * [[InventoryReservationFailedPayload]] and compensates by releasing any
 * already-reserved items (saga compensation pattern).
 */
final class InventoryEventConsumer(
    manager: InventoryManager,
    producer: InventoryEventProducer,
    tracer: Tracer,
)(using system: ActorSystem[?]):

  private val log                      = LoggerFactory.getLogger(getClass)
  private given ec: ExecutionContext   = system.executionContext
  private given timeout: Timeout       = Timeout.create(
    system.settings.config.getDuration("inventory.ask-timeout")
  )
  private val config                   = system.settings.config
  private val kafkaCfg                 = config.getConfig("akka.kafka.consumer")
  private val topic                    = config.getString("inventory.kafka.order-events-topic")

  private val consumerSettings: ConsumerSettings[String, Array[Byte]] =
    ConsumerSettings(kafkaCfg, new StringDeserializer, new ByteArrayDeserializer)
      .withGroupId(config.getString("inventory.kafka.consumer-group-id"))
      .withBootstrapServers(config.getString("inventory.kafka.bootstrap-servers"))

  private val committerSettings: CommitterSettings =
    CommitterSettings(system)

  /** Start the consumer stream; returns a kill switch for graceful shutdown. */
  def start(): UniqueKillSwitch =
    log.info("Starting Kafka consumer on topic={}", topic)

    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .viaMat(KillSwitches.single)(Keep.right)
      .mapAsync(parallelism = 4) { msg =>
        processRecord(msg.record)
          .recover { case ex =>
            log.error("Error processing record offset={}: {}", msg.record.offset(), ex.getMessage, ex)
          }
          .map(_ => msg.committableOffset)
      }
      .via(Committer.flow(committerSettings))
      .toMat(akka.stream.scaladsl.Sink.ignore)(Keep.left)
      .run()

  // ── Record dispatch ────────────────────────────────────────────────────

  private def processRecord(record: ConsumerRecord[String, Array[Byte]]): Future[Unit] =
    val rawJson = new String(record.value(), "UTF-8")

    val span = tracer
      .spanBuilder("kafka.consume order-events")
      .setSpanKind(SpanKind.CONSUMER)
      .setAttribute("messaging.system", "kafka")
      .setAttribute("messaging.destination", topic)
      .setAttribute("messaging.kafka.offset", record.offset())
      .startSpan()
    val scope = span.makeCurrent()

    val result = Try(rawJson.parseJson.asJsObject) match
      case Failure(ex) =>
        log.warn("Failed to parse Kafka record as JSON offset={}: {}", record.offset(), ex.getMessage)
        Future.unit

      case Success(json) =>
        json.fields.get("eventType").map(_.convertTo[String]).getOrElse("UNKNOWN") match
          case "ORDER_CREATED"   => handleOrderCreated(json)
          case "ORDER_CANCELLED" => handleOrderCancelled(json)
          case "ORDER_CONFIRMED" => handleOrderConfirmed(json)
          case other =>
            log.debug("Ignoring unknown event type={}", other)
            Future.unit

    result.onComplete { _ =>
      scope.close()
      span.end()
    }
    result

  // ── Event handlers ─────────────────────────────────────────────────────

  /** Reserve inventory for every item in the order; compensate on failure. */
  private def handleOrderCreated(json: JsObject): Future[Unit] =
    val payload = json.fields("payload").convertTo[String].parseJson.convertTo[OrderCreatedPayload]
    log.info("ORDER_CREATED orderId={} items={}", payload.orderId, payload.items.size)

    val inventoryId = java.util.UUID.randomUUID().toString

    // Fan-out reservation requests in parallel.
    Future
      .traverse(payload.items) { item =>
        manager
          .reserve(item.productId, payload.orderId, item.quantity)
          .map(reply => (item.productId, reply))
          .recover { case ex => (item.productId, akka.pattern.StatusReply.error(ex)) }
      }
      .flatMap { results =>
        val failures = results.collect {
          case (pid, r) if !r.isSuccess => s"$pid: ${r.getError.getMessage}"
        }

        if failures.isEmpty then
          log.info("All items reserved for orderId={}", payload.orderId)
          producer.publishReserved(inventoryId, payload.orderId)
        else
          log.warn("Reservation failed for orderId={}: {}", payload.orderId, failures.mkString(", "))
          // Compensate: release already-reserved items.
          val succeeded = results.collect { case (pid, r) if r.isSuccess => pid }
          Future
            .traverse(succeeded)(pid =>
              manager.release(pid, payload.orderId).recover { case ex =>
                log.error("Compensation release failed product={} order={}: {}", pid, payload.orderId, ex.getMessage)
                akka.pattern.StatusReply.error(ex)
              }
            )
            .flatMap(_ =>
              producer.publishReservationFailed(
                inventoryId,
                payload.orderId,
                reason = s"Insufficient stock: ${failures.mkString(", ")}",
              )
            )
      }

  /** Release all reservations when an order is cancelled. */
  private def handleOrderCancelled(json: JsObject): Future[Unit] =
    val payload = json.fields("payload").convertTo[String].parseJson.convertTo[OrderCancelledPayload]
    log.info("ORDER_CANCELLED orderId={} items={}", payload.orderId, payload.items.size)

    if payload.items.isEmpty then
      log.warn("ORDER_CANCELLED event for orderId={} has no items — cannot release reservations", payload.orderId)
      Future.unit
    else
      Future
        .traverse(payload.items) { item =>
          manager
            .release(item.productId, payload.orderId)
            .map(r => (item.productId, r))
            .recover { case ex => (item.productId, akka.pattern.StatusReply.error(ex)) }
        }
        .map { results =>
          val failures = results.collect { case (pid, r) if !r.isSuccess => s"$pid: ${r.getError.getMessage}" }
          if failures.nonEmpty then
            log.error("Some releases failed for orderId={}: {}", payload.orderId, failures.mkString(", "))
          else
            log.info("All reservations released for orderId={}", payload.orderId)
        }

  /** Confirm (finalise) reservations when an order is confirmed / ready for fulfilment. */
  private def handleOrderConfirmed(json: JsObject): Future[Unit] =
    val payload = json.fields("payload").convertTo[String].parseJson.convertTo[OrderConfirmedPayload]
    log.info("ORDER_CONFIRMED orderId={} items={}", payload.orderId, payload.items.size)

    if payload.items.isEmpty then
      log.warn("ORDER_CONFIRMED event for orderId={} has no items — cannot confirm reservations", payload.orderId)
      Future.unit
    else
      Future
        .traverse(payload.items) { item =>
          manager
            .confirm(item.productId, payload.orderId)
            .map(r => (item.productId, r))
            .recover { case ex => (item.productId, akka.pattern.StatusReply.error(ex)) }
        }
        .map { results =>
          val failures = results.collect { case (pid, r) if !r.isSuccess => s"$pid: ${r.getError.getMessage}" }
          if failures.nonEmpty then
            log.error("Some confirmations failed for orderId={}: {}", payload.orderId, failures.mkString(", "))
          else
            log.info("All reservations confirmed for orderId={}", payload.orderId)
        }
