package com.ecommerce.analytics

import akka.actor.ActorSystem
import com.ecommerce.analytics.api.AnalyticsRoutes
import com.ecommerce.analytics.repository.MetricsRepository
import com.ecommerce.analytics.stream.{AnalyticsPipeline, OrderEventStream}
import com.typesafe.config.ConfigFactory
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.slf4j.LoggerFactory
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object Main extends App {
  private val log = LoggerFactory.getLogger(getClass)
  val config = ConfigFactory.load()

  val otel: OpenTelemetrySdk =
    AutoConfiguredOpenTelemetrySdk.builder()
      .addPropertiesSupplier(() => java.util.Map.of(
        "otel.service.name",             config.getString("opentelemetry.service-name"),
        "otel.exporter.otlp.endpoint",   config.getString("opentelemetry.otlp-endpoint")
      ))
      .build()
      .getOpenTelemetrySdk

  given system: ActorSystem  = ActorSystem("analytics-system", config)
  given ec: ExecutionContext = system.dispatcher

  val db         = Database.forConfig("database.db", config)
  val repository = new MetricsRepository(db)
  val eventStream = new OrderEventStream(config)
  val pipeline    = new AnalyticsPipeline(eventStream, repository, config)
  val routes      = new AnalyticsRoutes(repository)
  val host        = config.getString("analytics.http.host")
  val port        = config.getInt("analytics.http.port")

  for {
    _       <- repository.initialize()
    binding <- routes.startServer(host, port)
  } yield {
    log.info("Analytics service started on {}:{}", host, port)
    val (killSwitch, done) = pipeline.run()
    done.onComplete {
      case Success(_) => log.info("Analytics pipeline completed")
      case Failure(e) => log.error("Analytics pipeline failed", e)
    }
    sys.addShutdownHook {
      log.info("Shutting down analytics service...")
      killSwitch.shutdown()
      binding.unbind()
      db.close()
      system.terminate()
    }
  }
}
