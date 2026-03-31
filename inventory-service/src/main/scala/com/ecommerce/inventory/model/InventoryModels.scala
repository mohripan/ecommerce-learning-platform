package com.ecommerce.inventory.model

import spray.json.*
import java.time.Instant

// ── Marker trait for Akka Jackson-CBOR serialization ──────────────────────
trait CborSerializable

// ── Domain value objects ───────────────────────────────────────────────────

/** A single item line requested for reservation. */
final case class OrderItem(productId: String, quantity: Int)

/** Current state snapshot returned via the HTTP API. */
final case class InventorySnapshot(
    productId: String,
    availableStock: Int,
    reservedStock: Int,
    totalStock: Int,
    reservations: Map[String, Int], // orderId → quantity
)

/** A recorded reservation entry kept in the actor state. */
final case class ReservationEntry(orderId: String, quantity: Int)

// ── HTTP request/response DTOs ─────────────────────────────────────────────

final case class ReserveRequest(orderId: String, quantity: Int)
final case class ReleaseRequest(orderId: String)
final case class ConfirmRequest(orderId: String)
final case class ReplenishRequest(quantity: Int)
final case class BatchReserveRequest(orderId: String, items: List[OrderItem])

final case class OperationResponse(success: Boolean, message: String)

// ── Kafka event envelopes (cross-service contract) ─────────────────────────

/** Events consumed from the `order-events` topic. */
enum OrderEventType:
  case ORDER_CREATED, ORDER_CANCELLED, ORDER_CONFIRMED, ORDER_SHIPPED, UNKNOWN

final case class KafkaOrderItem(productId: String, quantity: Int)

final case class OrderCreatedPayload(
    orderId: String,
    customerId: String,
    items: List[KafkaOrderItem],
    totalAmount: BigDecimal,
    shippingAddress: String,
    createdAt: String,
)

final case class OrderCancelledPayload(orderId: String, reason: String, items: List[KafkaOrderItem] = Nil)

/** Sent when an order is confirmed / ready for fulfilment; contains the item list for confirmation. */
final case class OrderConfirmedPayload(orderId: String, items: List[KafkaOrderItem])

final case class InboundOrderEvent(
    eventType: String,
    payload: String, // raw JSON string; parsed per eventType
)

/** Events produced to the `inventory-events` topic. */
sealed trait OutboundInventoryEvent:
  def eventType: String
  def orderId: String
  def timestamp: String

final case class InventoryReservedPayload(
    eventType: String,
    inventoryId: String,
    orderId: String,
    timestamp: String,
) extends OutboundInventoryEvent

final case class InventoryReservationFailedPayload(
    eventType: String,
    inventoryId: String,
    orderId: String,
    reason: String,
    timestamp: String,
) extends OutboundInventoryEvent

// ── spray-json protocol ────────────────────────────────────────────────────

object JsonProtocol extends DefaultJsonProtocol:

  given orderItemFormat: RootJsonFormat[OrderItem]               = jsonFormat2(OrderItem.apply)
  given inventorySnapshotFormat: RootJsonFormat[InventorySnapshot] =
    jsonFormat5(InventorySnapshot.apply)
  given reserveRequestFormat: RootJsonFormat[ReserveRequest]     = jsonFormat2(ReserveRequest.apply)
  given releaseRequestFormat: RootJsonFormat[ReleaseRequest]     = jsonFormat1(ReleaseRequest.apply)
  given confirmRequestFormat: RootJsonFormat[ConfirmRequest]     = jsonFormat1(ConfirmRequest.apply)
  given replenishRequestFormat: RootJsonFormat[ReplenishRequest] = jsonFormat1(ReplenishRequest.apply)
  given batchReserveRequestFormat: RootJsonFormat[BatchReserveRequest] =
    jsonFormat2(BatchReserveRequest.apply)
  given operationResponseFormat: RootJsonFormat[OperationResponse] =
    jsonFormat2(OperationResponse.apply)

  // Kafka inbound
  given kafkaOrderItemFormat: RootJsonFormat[KafkaOrderItem] = jsonFormat2(KafkaOrderItem.apply)
  given orderCreatedPayloadFormat: RootJsonFormat[OrderCreatedPayload] =
    jsonFormat6(OrderCreatedPayload.apply)
  given orderCancelledPayloadFormat: RootJsonFormat[OrderCancelledPayload] =
    jsonFormat3(OrderCancelledPayload.apply)
  given orderConfirmedPayloadFormat: RootJsonFormat[OrderConfirmedPayload] =
    jsonFormat2(OrderConfirmedPayload.apply)

  // Kafka outbound
  given inventoryReservedFormat: RootJsonFormat[InventoryReservedPayload] =
    jsonFormat4(InventoryReservedPayload.apply)
  given inventoryReservationFailedFormat: RootJsonFormat[InventoryReservationFailedPayload] =
    jsonFormat5(InventoryReservationFailedPayload.apply)
