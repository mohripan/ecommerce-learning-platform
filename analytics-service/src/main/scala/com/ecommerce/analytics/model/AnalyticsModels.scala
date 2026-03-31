package com.ecommerce.analytics.model

import java.time.Instant

case class OrderItem(productId: String, productName: String, quantity: Int, unitPrice: BigDecimal)

case class OrderCreatedEvent(orderId: String, customerId: String, items: List[OrderItem], totalAmount: BigDecimal, createdAt: Instant)

case class OrderStatusEvent(orderId: String, customerId: String, status: String, totalAmount: BigDecimal, updatedAt: Instant)

sealed trait OrderEvent {
  def orderId: String
  def customerId: String
  def totalAmount: BigDecimal
  def eventTime: Instant
}

case class NormalisedOrderEvent(
  orderId: String, customerId: String, totalAmount: BigDecimal,
  items: List[OrderItem], status: String, eventTime: Instant
) extends OrderEvent

case class WindowKey(windowStart: Instant, windowEnd: Instant)

case class OrderMetricsAccumulator(
  windowKey: WindowKey,
  orderCount: Long = 0L,
  totalRevenue: BigDecimal = BigDecimal(0),
  productCounts: Map[String, Long] = Map.empty,
  productRevenue: Map[String, BigDecimal] = Map.empty
) {
  def add(event: NormalisedOrderEvent): OrderMetricsAccumulator =
    copy(
      orderCount = orderCount + 1,
      totalRevenue = totalRevenue + event.totalAmount,
      productCounts = event.items.foldLeft(productCounts) { (acc, item) =>
        acc.updated(item.productId, acc.getOrElse(item.productId, 0L) + item.quantity)
      },
      productRevenue = event.items.foldLeft(productRevenue) { (acc, item) =>
        val revenue = item.unitPrice * item.quantity
        acc.updated(item.productId, acc.getOrElse(item.productId, BigDecimal(0)) + revenue)
      }
    )
}

case class HourlyMetrics(id: Option[Long], windowStart: Instant, windowEnd: Instant, orderCount: Long, totalRevenue: BigDecimal, avgOrderValue: BigDecimal, createdAt: Instant = Instant.now())

case class TopProduct(productId: String, totalQuantity: Long, totalRevenue: BigDecimal, windowStart: Instant, windowEnd: Instant)

case class RevenueQuery(from: Instant, to: Instant)

case class RevenueResponse(from: Instant, to: Instant, totalRevenue: BigDecimal, orderCount: Long, avgOrderValue: BigDecimal)

case class TopProductsResponse(from: Instant, to: Instant, products: List[TopProduct])

case class HealthResponse(status: String, timestamp: Instant = Instant.now())
