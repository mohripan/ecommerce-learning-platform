package com.ecommerce.analytics.stream

import akka.actor.ActorSystem
import akka.stream.scaladsl.*
import akka.stream.{KillSwitches, UniqueKillSwitch}
import akka.Done
import com.ecommerce.analytics.model.*
import com.ecommerce.analytics.repository.MetricsRepository
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import java.time.{Instant, ZoneOffset}
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

class AnalyticsPipeline(
  eventStream: OrderEventStream,
  repository: MetricsRepository,
  config: Config
)(using system: ActorSystem, ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)

  private val windowDurationSeconds: Long =
    config.getDuration("analytics.pipeline.window-duration-seconds", java.util.concurrent.TimeUnit.SECONDS)

  private val flushInterval: FiniteDuration =
    config.getDuration("analytics.pipeline.flush-interval-seconds", java.util.concurrent.TimeUnit.SECONDS).seconds

  private val batchSize = 200

  private def windowFor(eventTime: Instant): WindowKey = {
    val windowStart = eventTime.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).toInstant
    val windowEnd   = windowStart.plus(windowDurationSeconds, ChronoUnit.SECONDS)
    WindowKey(windowStart, windowEnd)
  }

  def run(): (UniqueKillSwitch, Future[Done]) = {
    log.info("Starting analytics pipeline with window={}s flush={}s", windowDurationSeconds, flushInterval.toSeconds)

    eventStream.source
      .viaMat(KillSwitches.single)(Keep.right)
      .groupedWithin(batchSize, flushInterval)
      .mapAsync(1) { batch =>
        val accumulators = batch.foldLeft(Map.empty[WindowKey, OrderMetricsAccumulator]) { (acc, event) =>
          val key     = windowFor(event.eventTime)
          val current = acc.getOrElse(key, OrderMetricsAccumulator(key))
          acc.updated(key, current.add(event))
        }
        persistBatch(accumulators)
      }
      .toMat(Sink.ignore)(Keep.both)
      .run()
  }

  private def persistBatch(accumulators: Map[WindowKey, OrderMetricsAccumulator]): Future[Done] = {
    val futures = accumulators.values.toList.map { acc =>
      val avg = if (acc.orderCount > 0) acc.totalRevenue / acc.orderCount else BigDecimal(0)
      val metrics = HourlyMetrics(None, acc.windowKey.windowStart, acc.windowKey.windowEnd, acc.orderCount, acc.totalRevenue, avg)
      val topProductList = acc.productRevenue.map { (productId, revenue) =>
        TopProduct(productId, acc.productCounts.getOrElse(productId, 0L), revenue, acc.windowKey.windowStart, acc.windowKey.windowEnd)
      }.toList
      for {
        _ <- repository.upsertHourlyMetrics(metrics)
        _ <- repository.upsertTopProducts(topProductList)
      } yield ()
    }
    Future.sequence(futures).map { _ =>
      log.debug("Persisted metrics for {} windows", accumulators.size)
      Done
    }.recover { case e =>
      log.error("Failed to persist batch metrics", e)
      Done
    }
  }
}
