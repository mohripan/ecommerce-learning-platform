package com.ecommerce.analytics.stream

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.*
import akka.stream.{ActorAttributes, Supervision}
import com.ecommerce.analytics.model.*
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import spray.json.*

import java.time.Instant
import scala.util.{Failure, Success, Try}

object EventJsonProtocol extends DefaultJsonProtocol {
  given JsonFormat[Instant] with
    def write(i: Instant): JsValue = JsString(i.toString)
    def read(v: JsValue): Instant  = v match
      case JsString(s) => Instant.parse(s)
      case _           => deserializationError("Instant expected")

  given JsonFormat[OrderItem]         = jsonFormat4(OrderItem.apply)
  given JsonFormat[OrderCreatedEvent] = jsonFormat5(OrderCreatedEvent.apply)
  given JsonFormat[OrderStatusEvent]  = jsonFormat5(OrderStatusEvent.apply)
}

class OrderEventStream(config: Config)(using system: ActorSystem) {
  import EventJsonProtocol.given
  private val log = LoggerFactory.getLogger(getClass)

  private val consumerSettings: ConsumerSettings[String, String] =
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(config.getString("kafka.bootstrap-servers"))
      .withGroupId(config.getString("kafka.consumer.group-id"))
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.getString("kafka.consumer.auto-offset-reset"))
      .withProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.getString("kafka.consumer.max-poll-records"))

  private val topics: Set[String] =
    import scala.jdk.CollectionConverters.*
    config.getStringList("kafka.consumer.topics").asScala.toSet

  private val bufferSize: Int = config.getInt("analytics.pipeline.buffer-size")

  val source: Source[NormalisedOrderEvent, Consumer.Control] =
    Consumer
      .plainSource(consumerSettings, Subscriptions.topics(topics))
      .map(record => parseEvent(record.topic(), record.key(), record.value()))
      .collect { case Some(event) => event }
      .buffer(bufferSize, akka.stream.OverflowStrategy.backpressure)
      .withAttributes(ActorAttributes.supervisionStrategy {
        case e: spray.json.JsonParser.ParsingException =>
          log.warn("Skipping malformed JSON: {}", e.getMessage)
          Supervision.Resume
        case e: Exception =>
          log.error("Unexpected stream error", e)
          Supervision.Resume
      })

  private def parseEvent(topic: String, key: String, value: String): Option[NormalisedOrderEvent] =
    Try {
      val json   = value.parseJson.asJsObject
      val status = topicToStatus(topic)
      topic match {
        case "order.created" =>
          val ev = json.convertTo[OrderCreatedEvent]
          NormalisedOrderEvent(ev.orderId, ev.customerId, ev.totalAmount, ev.items, status, ev.createdAt)
        case _ =>
          val ev = json.convertTo[OrderStatusEvent]
          NormalisedOrderEvent(ev.orderId, ev.customerId, ev.totalAmount, Nil, status, ev.updatedAt)
      }
    } match {
      case Success(event) => Some(event)
      case Failure(e)     =>
        log.warn("Failed to parse event from topic={} key={}: {}", topic, key, e.getMessage)
        None
    }

  private def topicToStatus(topic: String): String = topic match {
    case "order.created"   => "created"
    case "order.confirmed" => "confirmed"
    case "order.shipped"   => "shipped"
    case "order.cancelled" => "cancelled"
    case other             => other
  }
}
