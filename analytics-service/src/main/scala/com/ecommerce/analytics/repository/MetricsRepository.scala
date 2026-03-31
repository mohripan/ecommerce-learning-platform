package com.ecommerce.analytics.repository

import com.ecommerce.analytics.model.*
import slick.jdbc.PostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class HourlyMetricsTable(tag: Tag) extends Table[HourlyMetrics](tag, "hourly_metrics") {
  given MappedColumnType[Instant, java.sql.Timestamp] =
    MappedColumnType.base[Instant, java.sql.Timestamp](i => java.sql.Timestamp.from(i), t => t.toInstant)

  def id           = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def windowStart  = column[Instant]("window_start")
  def windowEnd    = column[Instant]("window_end")
  def orderCount   = column[Long]("order_count")
  def totalRevenue = column[BigDecimal]("total_revenue")
  def avgOrder     = column[BigDecimal]("avg_order_value")
  def createdAt    = column[Instant]("created_at")

  def *: ProvenShape[HourlyMetrics] =
    (id.?, windowStart, windowEnd, orderCount, totalRevenue, avgOrder, createdAt).mapTo[HourlyMetrics]
}

class TopProductsTable(tag: Tag) extends Table[TopProduct](tag, "top_products") {
  given MappedColumnType[Instant, java.sql.Timestamp] =
    MappedColumnType.base[Instant, java.sql.Timestamp](i => java.sql.Timestamp.from(i), t => t.toInstant)

  def productId    = column[String]("product_id")
  def totalQty     = column[Long]("total_quantity")
  def totalRevenue = column[BigDecimal]("total_revenue")
  def windowStart  = column[Instant]("window_start")
  def windowEnd    = column[Instant]("window_end")

  def pk = primaryKey("pk_top_products", (productId, windowStart))

  def *: ProvenShape[TopProduct] =
    (productId, totalQty, totalRevenue, windowStart, windowEnd).mapTo[TopProduct]
}

class MetricsRepository(db: Database)(using ExecutionContext) {

  private val hourlyMetrics = TableQuery[HourlyMetricsTable]
  private val topProducts   = TableQuery[TopProductsTable]

  def initialize(): Future[Unit] =
    db.run(DBIO.seq(hourlyMetrics.schema.createIfNotExists, topProducts.schema.createIfNotExists))

  def upsertHourlyMetrics(metrics: HourlyMetrics): Future[Unit] =
    db.run(
      hourlyMetrics
        .filter(r => r.windowStart === metrics.windowStart && r.windowEnd === metrics.windowEnd)
        .result.headOption
        .flatMap {
          case Some(_) =>
            hourlyMetrics
              .filter(r => r.windowStart === metrics.windowStart)
              .map(r => (r.orderCount, r.totalRevenue, r.avgOrder))
              .update((metrics.orderCount, metrics.totalRevenue, metrics.avgOrderValue))
              .map(_ => ())
          case None =>
            (hourlyMetrics += metrics).map(_ => ())
        }
        .transactionally
    )

  def upsertTopProducts(products: List[TopProduct]): Future[Unit] =
    db.run(DBIO.seq(products.map(p => topProducts.insertOrUpdate(p))*).transactionally)

  def queryRevenue(from: Instant, to: Instant): Future[RevenueResponse] =
    db.run(
      hourlyMetrics
        .filter(r => r.windowStart >= from && r.windowEnd <= to)
        .map(r => (r.totalRevenue, r.orderCount))
        .result
    ).map { rows =>
      val totalRevenue = rows.map(_._1).sum
      val totalOrders  = rows.map(_._2).sum
      val avg          = if (totalOrders > 0) totalRevenue / totalOrders else BigDecimal(0)
      RevenueResponse(from, to, totalRevenue, totalOrders, avg)
    }

  def queryTopProducts(from: Instant, to: Instant, limit: Int = 10): Future[List[TopProduct]] =
    db.run(
      topProducts
        .filter(p => p.windowStart >= from && p.windowEnd <= to)
        .sortBy(_.totalRevenue.desc)
        .take(limit)
        .result
    ).map(_.toList)

  def queryHourlyMetrics(from: Instant, to: Instant): Future[List[HourlyMetrics]] =
    db.run(
      hourlyMetrics
        .filter(r => r.windowStart >= from && r.windowEnd <= to)
        .sortBy(_.windowStart)
        .result
    ).map(_.toList)
}
