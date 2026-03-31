package com.ecommerce.analytics.api

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import com.ecommerce.analytics.model.*
import com.ecommerce.analytics.repository.MetricsRepository
import org.slf4j.LoggerFactory
import spray.json.*

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ApiJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  given JsonFormat[Instant] with
    def write(i: Instant): JsValue = JsString(i.toString)
    def read(v: JsValue): Instant  = v match
      case JsString(s) => Instant.parse(s)
      case _           => deserializationError("Instant expected")

  given RootJsonFormat[HourlyMetrics]       = jsonFormat7(HourlyMetrics.apply)
  given RootJsonFormat[TopProduct]          = jsonFormat5(TopProduct.apply)
  given RootJsonFormat[RevenueResponse]     = jsonFormat5(RevenueResponse.apply)
  given RootJsonFormat[TopProductsResponse] = jsonFormat3(TopProductsResponse.apply)
  given RootJsonFormat[HealthResponse]      = jsonFormat2(HealthResponse.apply)
}

class AnalyticsRoutes(repository: MetricsRepository)(using system: ActorSystem, ec: ExecutionContext) {
  import ApiJsonProtocol.given
  private val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    pathPrefix("api" / "v1") {
      concat(
        path("health") {
          get { complete(HealthResponse("ok")) }
        },
        path("analytics" / "revenue") {
          get {
            parameters("from".as[String], "to".as[String]) { (fromStr, toStr) =>
              parseInstants(fromStr, toStr) match {
                case Left(err) => complete(StatusCodes.BadRequest, s"""{"error":"$err"}""")
                case Right((from, to)) =>
                  onComplete(repository.queryRevenue(from, to)) {
                    case Success(response) => complete(response)
                    case Failure(e) =>
                      log.error("Revenue query failed", e)
                      complete(StatusCodes.InternalServerError, s"""{"error":"${e.getMessage}"}""")
                  }
              }
            }
          }
        },
        path("analytics" / "top-products") {
          get {
            parameters("from".as[String], "to".as[String], "limit".as[Int].withDefault(10)) { (fromStr, toStr, limit) =>
              parseInstants(fromStr, toStr) match {
                case Left(err) => complete(StatusCodes.BadRequest, s"""{"error":"$err"}""")
                case Right((from, to)) =>
                  onComplete(repository.queryTopProducts(from, to, limit)) {
                    case Success(products) => complete(TopProductsResponse(from, to, products))
                    case Failure(e) =>
                      log.error("Top products query failed", e)
                      complete(StatusCodes.InternalServerError, s"""{"error":"${e.getMessage}"}""")
                  }
              }
            }
          }
        },
        path("analytics" / "hourly") {
          get {
            parameters("from".as[String], "to".as[String]) { (fromStr, toStr) =>
              parseInstants(fromStr, toStr) match {
                case Left(err) => complete(StatusCodes.BadRequest, s"""{"error":"$err"}""")
                case Right((from, to)) =>
                  onComplete(repository.queryHourlyMetrics(from, to)) {
                    case Success(metrics) => complete(metrics)
                    case Failure(e) =>
                      log.error("Hourly metrics query failed", e)
                      complete(StatusCodes.InternalServerError, s"""{"error":"${e.getMessage}"}""")
                  }
              }
            }
          }
        }
      )
    }

  def startServer(host: String, port: Int): Future[Http.ServerBinding] =
    Http().newServerAt(host, port).bind(routes).andThen {
      case Success(binding) => log.info("Analytics HTTP server bound to {}:{}", host, port)
      case Failure(e)       => log.error("Failed to bind HTTP server", e)
    }

  private def parseInstants(fromStr: String, toStr: String): Either[String, (Instant, Instant)] =
    for {
      from <- Try(Instant.parse(fromStr)).toEither.left.map(e => s"Invalid 'from': ${e.getMessage}")
      to   <- Try(Instant.parse(toStr)).toEither.left.map(e => s"Invalid 'to': ${e.getMessage}")
      _    <- Either.cond(from.isBefore(to), (), "'from' must be before 'to'")
    } yield (from, to)
}
