package com.ecommerce.inventory.api

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.util.Timeout
import com.ecommerce.inventory.actor.InventoryManager
import com.ecommerce.inventory.model.JsonProtocol.given
import com.ecommerce.inventory.model.*
import io.opentelemetry.api.trace.{SpanKind, StatusCode as OtelStatusCode, Tracer}
import spray.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Akka HTTP routes for the Inventory Service REST API.
 *
 * All routes follow the pattern: extract → validate → ask actor → respond.
 * OpenTelemetry spans wrap each route handler for distributed tracing.
 *
 * Base path: /api/v1/inventory
 *
 * Endpoints:
 *   GET    /api/v1/inventory/:productId                  — current snapshot
 *   POST   /api/v1/inventory/:productId/reserve          — reserve stock
 *   POST   /api/v1/inventory/:productId/release          — release reservation
 *   POST   /api/v1/inventory/:productId/confirm          — confirm reservation
 *   POST   /api/v1/inventory/:productId/replenish        — add stock
 *   POST   /api/v1/inventory/batch/reserve               — reserve multiple products
 *   GET    /api/v1/health                                — liveness probe
 */
final class InventoryRoutes(
    manager: InventoryManager,
    tracer: Tracer,
)(using system: ActorSystem[?]):

  private given ec: ExecutionContext = system.executionContext
  private given timeout: Timeout     = Timeout.create(
    system.settings.config.getDuration("inventory.ask-timeout")
  )

  // ── Exception & rejection handling ────────────────────────────────────

  private val exceptionHandler: ExceptionHandler = ExceptionHandler {
    case ex: IllegalArgumentException =>
      complete(StatusCodes.BadRequest, OperationResponse(success = false, ex.getMessage))
    case ex =>
      system.log.error("Unhandled exception in route", ex)
      complete(StatusCodes.InternalServerError, OperationResponse(success = false, "Internal error"))
  }

  // ── Public route tree ─────────────────────────────────────────────────

  val routes: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("api" / "v1") {
        concat(
          healthRoute,
          inventoryRoutes,
        )
      }
    }

  // ── /health ───────────────────────────────────────────────────────────

  private val healthRoute: Route =
    path("health") {
      get {
        complete(StatusCodes.OK, OperationResponse(success = true, "Inventory service is healthy"))
      }
    }

  // ── /inventory ────────────────────────────────────────────────────────

  private val inventoryRoutes: Route =
    pathPrefix("inventory") {
      concat(
        batchReserveRoute,
        pathPrefix(Segment) { productId =>
          concat(
            getInventoryRoute(productId),
            reserveRoute(productId),
            releaseRoute(productId),
            confirmRoute(productId),
            replenishRoute(productId),
          )
        },
      )
    }

  // GET /inventory/:productId
  private def getInventoryRoute(productId: String): Route =
    get {
      pathEndOrSingleSlash {
        withSpan(s"GET /inventory/$productId") {
          onComplete(manager.getSnapshot(productId)) {
            case Success(snapshot) => complete(snapshot)
            case Failure(ex)       => failWith(ex)
          }
        }
      }
    }

  // POST /inventory/:productId/reserve
  private def reserveRoute(productId: String): Route =
    (post & path("reserve")) {
      entity(as[ReserveRequest]) { req =>
        withSpan(s"POST /inventory/$productId/reserve") {
          validateRequest(req.quantity > 0, "quantity must be positive") {
            onComplete(manager.reserve(productId, req.orderId, req.quantity)) {
              case Success(reply) if reply.isSuccess =>
                complete(
                  StatusCodes.OK,
                  OperationResponse(success = true, s"Reserved ${req.quantity} units of $productId for order ${req.orderId}"),
                )
              case Success(reply) =>
                complete(
                  StatusCodes.Conflict,
                  OperationResponse(success = false, reply.getError.getMessage),
                )
              case Failure(ex) => failWith(ex)
            }
          }
        }
      }
    }

  // POST /inventory/:productId/release
  private def releaseRoute(productId: String): Route =
    (post & path("release")) {
      entity(as[ReleaseRequest]) { req =>
        withSpan(s"POST /inventory/$productId/release") {
          onComplete(manager.release(productId, req.orderId)) {
            case Success(reply) if reply.isSuccess =>
              complete(OperationResponse(success = true, s"Released reservation for order ${req.orderId}"))
            case Success(reply) =>
              complete(StatusCodes.NotFound, OperationResponse(success = false, reply.getError.getMessage))
            case Failure(ex) => failWith(ex)
          }
        }
      }
    }

  // POST /inventory/:productId/confirm
  private def confirmRoute(productId: String): Route =
    (post & path("confirm")) {
      entity(as[ConfirmRequest]) { req =>
        withSpan(s"POST /inventory/$productId/confirm") {
          onComplete(manager.confirm(productId, req.orderId)) {
            case Success(reply) if reply.isSuccess =>
              complete(OperationResponse(success = true, s"Confirmed reservation for order ${req.orderId}"))
            case Success(reply) =>
              complete(StatusCodes.NotFound, OperationResponse(success = false, reply.getError.getMessage))
            case Failure(ex) => failWith(ex)
          }
        }
      }
    }

  // POST /inventory/:productId/replenish
  private def replenishRoute(productId: String): Route =
    (post & path("replenish")) {
      entity(as[ReplenishRequest]) { req =>
        withSpan(s"POST /inventory/$productId/replenish") {
          validateRequest(req.quantity > 0, "quantity must be positive") {
            onComplete(manager.replenish(productId, req.quantity)) {
              case Success(reply) if reply.isSuccess =>
                complete(OperationResponse(success = true, s"Replenished $productId by ${req.quantity} units"))
              case Success(reply) =>
                complete(StatusCodes.BadRequest, OperationResponse(success = false, reply.getError.getMessage))
              case Failure(ex) => failWith(ex)
            }
          }
        }
      }
    }

  // POST /inventory/batch/reserve
  private val batchReserveRoute: Route =
    (post & path("batch" / "reserve")) {
      entity(as[BatchReserveRequest]) { req =>
        withSpan("POST /inventory/batch/reserve") {
          // Fan-out: reserve each product independently; roll back on any failure.
          val reservations: Future[List[Either[String, String]]] =
            Future.traverse(req.items) { item =>
              manager
                .reserve(item.productId, req.orderId, item.quantity)
                .map {
                  case r if r.isSuccess => Right(item.productId)
                  case r                => Left(s"${item.productId}: ${r.getError.getMessage}")
                }
                .recover { case ex => Left(s"${item.productId}: ${ex.getMessage}") }
            }

          onComplete(reservations) {
            case Failure(ex) => failWith(ex)
            case Success(results) =>
              val failures = results.collect { case Left(err) => err }
              if failures.isEmpty then
                complete(
                  StatusCodes.OK,
                  OperationResponse(success = true, s"All ${req.items.size} items reserved for order ${req.orderId}"),
                )
              else
                // Compensate: release already-reserved items on partial failure.
                val succeeded = results.collect { case Right(pid) => pid }
                succeeded.foreach(pid =>
                  manager.release(pid, req.orderId).failed.foreach(ex =>
                    system.log.warn("Compensation release failed for product={} order={}: {}", pid, req.orderId, ex.getMessage)
                  )
                )
                complete(
                  StatusCodes.Conflict,
                  OperationResponse(success = false, s"Batch reservation failed: ${failures.mkString("; ")}"),
                )
          }
        }
      }
    }

  // ── Helpers ────────────────────────────────────────────────────────────

  private def withSpan(operationName: String)(inner: Route): Route =
    extractRequestContext { ctx =>
      val span = tracer
        .spanBuilder(operationName)
        .setSpanKind(SpanKind.SERVER)
        .setAttribute("http.method", ctx.request.method.value)
        .setAttribute("http.url", ctx.request.uri.toString)
        .startSpan()
      val scope = span.makeCurrent()
      mapResponse { response =>
        span.setAttribute("http.status_code", response.status.intValue.toLong)
        if response.status.isFailure() then span.setStatus(OtelStatusCode.ERROR)
        scope.close()
        span.end()
        response
      }(inner)
    }

  private def validateRequest(condition: Boolean, errorMsg: String)(inner: Route): Route =
    if condition then inner
    else complete(StatusCodes.BadRequest, OperationResponse(success = false, errorMsg))
