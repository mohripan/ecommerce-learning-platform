package com.ecommerce.orderservice.api;

import com.ecommerce.orderservice.api.dto.CancelOrderRequest;
import com.ecommerce.orderservice.api.dto.CreateOrderRequest;
import com.ecommerce.orderservice.api.dto.ShipOrderRequest;
import com.ecommerce.orderservice.command.*;
import com.ecommerce.orderservice.model.OrderStatus;
import com.ecommerce.orderservice.query.FindAllOrdersQuery;
import com.ecommerce.orderservice.query.FindOrderQuery;
import com.ecommerce.orderservice.query.OrderSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.responsetypes.ResponseTypes;
import org.axonframework.queryhandling.QueryGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST interface for the Order Service.
 *
 * <p>Commands are dispatched via {@link CommandGateway} (write side).
 * Queries are dispatched via {@link QueryGateway} (read side).
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "Order lifecycle management")
public class OrderController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    // ─────────────────────────────────────────────────────────────────────────
    // Commands (Write side)
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Place a new order")
    public ResponseEntity<Void> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        log.info("Creating order orderId={} for customerId={}", orderId, request.customerId());

        commandGateway.sendAndWait(new CreateOrderCommand(
                orderId,
                request.customerId(),
                request.items(),
                request.shippingAddress()
        ));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(orderId)
                .toUri();

        return ResponseEntity.created(location).build();
    }

    @PutMapping("/{orderId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Confirm an order (admin / saga use)")
    public ResponseEntity<Void> confirmOrder(@PathVariable String orderId) {
        log.info("Confirming order orderId={}", orderId);
        commandGateway.sendAndWait(new ConfirmOrderCommand(orderId));
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Cancel an order")
    public ResponseEntity<Void> cancelOrder(
            @PathVariable String orderId,
            @Valid @RequestBody CancelOrderRequest request) {
        log.info("Cancelling order orderId={}, reason={}", orderId, request.reason());
        commandGateway.sendAndWait(new CancelOrderCommand(orderId, request.reason()));
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/{orderId}/ship")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Mark an order as shipped")
    public ResponseEntity<Void> shipOrder(
            @PathVariable String orderId,
            @Valid @RequestBody ShipOrderRequest request) {
        log.info("Shipping order orderId={}, tracking={}", orderId, request.trackingNumber());
        commandGateway.sendAndWait(new ShipOrderCommand(orderId, request.trackingNumber(), request.carrier()));
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/{orderId}/deliver")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Mark an order as delivered")
    public ResponseEntity<Void> deliverOrder(@PathVariable String orderId) {
        log.info("Delivering order orderId={}", orderId);
        commandGateway.sendAndWait(new DeliverOrderCommand(orderId));
        return ResponseEntity.accepted().build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries (Read side)
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID")
    public ResponseEntity<OrderSummary> getOrder(@PathVariable String orderId) {
        OrderSummary summary = queryGateway
                .query(new FindOrderQuery(orderId), ResponseTypes.instanceOf(OrderSummary.class))
                .join();
        return ResponseEntity.ok(summary);
    }

    @GetMapping
    @Operation(summary = "List orders with optional filters")
    public ResponseEntity<List<OrderSummary>> listOrders(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<OrderSummary> orders = queryGateway
                .query(new FindAllOrdersQuery(customerId, status, page, size),
                        ResponseTypes.multipleInstancesOf(OrderSummary.class))
                .join();
        return ResponseEntity.ok(orders);
    }
}
