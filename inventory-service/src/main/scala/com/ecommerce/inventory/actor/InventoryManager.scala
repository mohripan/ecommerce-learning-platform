package com.ecommerce.inventory.actor

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.pattern.StatusReply
import akka.util.Timeout
import com.ecommerce.inventory.actor.InventoryActor.*
import com.ecommerce.inventory.model.InventorySnapshot

import scala.concurrent.Future

/**
 * Bootstraps Akka Cluster Sharding for [[InventoryActor]] entities and
 * exposes a clean API that hides entity routing behind typed futures.
 *
 * Each unique `productId` maps to exactly one [[InventoryActor]] instance
 * across the cluster. The sharding layer routes messages to whichever node
 * currently owns the shard for that product.
 *
 * Usage:
 * {{{
 *   val manager = InventoryManager(system)
 *   manager.reserve("sku-42", "order-99", quantity = 3)
 * }}}
 */
final class InventoryManager(system: ActorSystem[?]):

  private val sharding: ClusterSharding = ClusterSharding(system)

  /** The type key identifies the sharded entity family. */
  val TypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command](InventoryActor.EntityTypeName)

  /**
   * Register the entity factory with cluster sharding.
   * Must be called exactly once during application startup.
   */
  def init(): Unit =
    sharding.init(
      Entity(TypeKey)(ctx => InventoryActor(ctx.entityId))
    )

  /** Obtain a typed [[EntityRef]] for the given product — never null. */
  def entityRef(productId: String): EntityRef[Command] =
    sharding.entityRefFor(TypeKey, productId)

  // ── Typed command helpers (ask pattern) ────────────────────────────────

  def reserve(productId: String, orderId: String, quantity: Int)(using
      timeout: Timeout,
  ): Future[StatusReply[ReservationAck]] =
    entityRef(productId).ask(ReserveStock(orderId, quantity, _))

  def release(productId: String, orderId: String)(using
      timeout: Timeout,
  ): Future[StatusReply[ReleaseAck]] =
    entityRef(productId).ask(ReleaseReservation(orderId, _))

  def confirm(productId: String, orderId: String)(using
      timeout: Timeout,
  ): Future[StatusReply[ConfirmAck]] =
    entityRef(productId).ask(ConfirmReservation(orderId, _))

  def replenish(productId: String, quantity: Int)(using
      timeout: Timeout,
  ): Future[StatusReply[ReplenishAck]] =
    entityRef(productId).ask(Replenish(quantity, _))

  def getSnapshot(productId: String)(using
      timeout: Timeout,
  ): Future[InventorySnapshot] =
    entityRef(productId).ask(GetInventory(_))

object InventoryManager:
  def apply(system: ActorSystem[?]): InventoryManager =
    val manager = new InventoryManager(system)
    manager.init()
    manager
