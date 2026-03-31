package com.ecommerce.inventory.actor

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import com.ecommerce.inventory.model.{CborSerializable, InventorySnapshot}

import java.time.{Duration, Instant}

/**
 * Persistent Akka Typed actor representing a single inventory item (product).
 *
 * Uses Event Sourcing via [[EventSourcedBehavior]]:
 *   - Commands describe intent (ReserveStock, ReleaseReservation, …)
 *   - Events record what happened and are persisted to the journal
 *   - State is rebuilt by replaying events
 *
 * Concurrent reservation conflicts are resolved by the actor's single-threaded
 * message processing guarantee: requests are serialised per product.
 */
object InventoryActor:

  // ── Entity type key (used by cluster sharding) ───────────────────────────
  val EntityTypeName = "InventoryItem"

  // ─────────────────────────────────────────────────────────────────────────
  // Commands
  // ─────────────────────────────────────────────────────────────────────────

  sealed trait Command extends CborSerializable

  /** Reserve `quantity` units for the given order. Idempotent on duplicate orderId. */
  final case class ReserveStock(
      orderId: String,
      quantity: Int,
      replyTo: ActorRef[StatusReply[ReservationAck]],
  ) extends Command

  /** Release a previously created reservation (e.g. order cancelled). */
  final case class ReleaseReservation(
      orderId: String,
      replyTo: ActorRef[StatusReply[ReleaseAck]],
  ) extends Command

  /**
   * Confirm a reservation: deducts from available stock and removes the
   * reservation entry (e.g. order shipped / fulfilled).
   */
  final case class ConfirmReservation(
      orderId: String,
      replyTo: ActorRef[StatusReply[ConfirmAck]],
  ) extends Command

  /** Add units to available stock (e.g. purchase order received). */
  final case class Replenish(
      quantity: Int,
      replyTo: ActorRef[StatusReply[ReplenishAck]],
  ) extends Command

  /** Read-only query returning the current state snapshot. */
  final case class GetInventory(replyTo: ActorRef[InventorySnapshot]) extends Command

  // ── Reply types ───────────────────────────────────────────────────────────

  final case class ReservationAck(productId: String, orderId: String, quantity: Int)
      extends CborSerializable
  final case class ReleaseAck(productId: String, orderId: String)      extends CborSerializable
  final case class ConfirmAck(productId: String, orderId: String)      extends CborSerializable
  final case class ReplenishAck(productId: String, newAvailable: Int)  extends CborSerializable

  // ─────────────────────────────────────────────────────────────────────────
  // Events  (persisted to the journal)
  // ─────────────────────────────────────────────────────────────────────────

  sealed trait Event extends CborSerializable

  final case class StockReserved(
      productId: String,
      orderId: String,
      quantity: Int,
      timestamp: Instant,
  ) extends Event

  final case class ReservationReleased(
      productId: String,
      orderId: String,
      quantity: Int,
      timestamp: Instant,
  ) extends Event

  final case class ReservationConfirmed(
      productId: String,
      orderId: String,
      quantity: Int,
      timestamp: Instant,
  ) extends Event

  final case class StockReplenished(
      productId: String,
      addedQuantity: Int,
      newAvailable: Int,
      timestamp: Instant,
  ) extends Event

  // ─────────────────────────────────────────────────────────────────────────
  // State
  // ─────────────────────────────────────────────────────────────────────────

  final case class InventoryState(
      productId: String,
      availableStock: Int,
      reservations: Map[String, Int], // orderId → reserved quantity
  ) extends CborSerializable:

    def reservedStock: Int = reservations.values.sum
    def totalStock: Int    = availableStock + reservedStock

    def toSnapshot: InventorySnapshot =
      InventorySnapshot(productId, availableStock, reservedStock, totalStock, reservations)

  object InventoryState:
    def empty(productId: String): InventoryState =
      InventoryState(productId, availableStock = 0, reservations = Map.empty)

  // ─────────────────────────────────────────────────────────────────────────
  // Behavior factory
  // ─────────────────────────────────────────────────────────────────────────

  def apply(productId: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, InventoryState](
      persistenceId  = PersistenceId.ofUniqueId(s"$EntityTypeName|$productId"),
      emptyState     = InventoryState.empty(productId),
      commandHandler = commandHandler(productId),
      eventHandler   = eventHandler,
    )
      // Tag each event so read-side processors can subscribe to the stream.
      .withTagger(_ => Set("inventory-event"))
      // Keep the last 2 snapshots; take one every 100 events to bound replay time.
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
      // Restart after failures with exponential back-off, up to 30 s.
      .onPersistFailure(
        SupervisorStrategy.restartWithBackoff(
          minBackoff = Duration.ofMillis(200),
          maxBackoff = Duration.ofSeconds(30),
          randomFactor = 0.1,
        )
      )

  // ─────────────────────────────────────────────────────────────────────────
  // Command handler
  // ─────────────────────────────────────────────────────────────────────────

  private def commandHandler(
      productId: String,
  ): (InventoryState, Command) => Effect[Event, InventoryState] =
    (state, command) =>
      command match

        case ReserveStock(orderId, quantity, replyTo) =>
          // Idempotency: if the reservation already exists return success.
          state.reservations.get(orderId) match
            case Some(existingQty) =>
              Effect.reply(replyTo)(
                StatusReply.success(ReservationAck(productId, orderId, existingQty))
              )

            case None if quantity <= 0 =>
              Effect.reply(replyTo)(
                StatusReply.error(s"Quantity must be positive, got $quantity")
              )

            case None if state.availableStock < quantity =>
              Effect.reply(replyTo)(
                StatusReply.error(
                  s"Insufficient stock for $productId: available=${state.availableStock}, requested=$quantity"
                )
              )

            case None =>
              val event = StockReserved(productId, orderId, quantity, Instant.now())
              Effect
                .persist(event)
                .thenReply(replyTo)(s => StatusReply.success(ReservationAck(productId, orderId, quantity)))

        case ReleaseReservation(orderId, replyTo) =>
          state.reservations.get(orderId) match
            case None =>
              // Idempotent: releasing a non-existent reservation is a no-op.
              Effect.reply(replyTo)(StatusReply.success(ReleaseAck(productId, orderId)))

            case Some(qty) =>
              val event = ReservationReleased(productId, orderId, qty, Instant.now())
              Effect
                .persist(event)
                .thenReply(replyTo)(_ => StatusReply.success(ReleaseAck(productId, orderId)))

        case ConfirmReservation(orderId, replyTo) =>
          state.reservations.get(orderId) match
            case None =>
              Effect.reply(replyTo)(
                StatusReply.error(s"No active reservation found for orderId=$orderId on product=$productId")
              )

            case Some(qty) =>
              val event = ReservationConfirmed(productId, orderId, qty, Instant.now())
              Effect
                .persist(event)
                .thenReply(replyTo)(_ => StatusReply.success(ConfirmAck(productId, orderId)))

        case Replenish(quantity, replyTo) =>
          if quantity <= 0 then
            Effect.reply(replyTo)(StatusReply.error(s"Replenish quantity must be positive, got $quantity"))
          else
            val newAvailable = state.availableStock + quantity
            val event        = StockReplenished(productId, quantity, newAvailable, Instant.now())
            Effect
              .persist(event)
              .thenReply(replyTo)(_ => StatusReply.success(ReplenishAck(productId, newAvailable)))

        case GetInventory(replyTo) =>
          Effect.reply(replyTo)(state.toSnapshot)

  // ─────────────────────────────────────────────────────────────────────────
  // Event handler (pure state transition — no side effects allowed here)
  // ─────────────────────────────────────────────────────────────────────────

  private val eventHandler: (InventoryState, Event) => InventoryState =
    (state, event) =>
      event match

        case StockReserved(_, orderId, quantity, _) =>
          state.copy(
            availableStock = state.availableStock - quantity,
            reservations   = state.reservations + (orderId -> quantity),
          )

        case ReservationReleased(_, orderId, quantity, _) =>
          state.copy(
            availableStock = state.availableStock + quantity,
            reservations   = state.reservations - orderId,
          )

        case ReservationConfirmed(_, orderId, _, _) =>
          // Stock was already deducted when reserved; just remove the reservation entry.
          state.copy(reservations = state.reservations - orderId)

        case StockReplenished(_, addedQty, _, _) =>
          state.copy(availableStock = state.availableStock + addedQty)
