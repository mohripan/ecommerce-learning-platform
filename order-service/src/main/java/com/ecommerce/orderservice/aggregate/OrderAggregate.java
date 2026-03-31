package com.ecommerce.orderservice.aggregate;

import com.ecommerce.orderservice.command.*;
import com.ecommerce.orderservice.event.*;
import com.ecommerce.orderservice.model.OrderItem;
import com.ecommerce.orderservice.model.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.eventsourcing.EventSourcingHandler;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.AggregateLifecycle;
import org.axonframework.spring.stereotype.Aggregate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Order aggregate — the transactional boundary for all order-related state changes.
 *
 * <p>All state mutations happen exclusively through events; the command handlers
 * validate business rules and emit events via {@link AggregateLifecycle#apply}, while
 * the {@link EventSourcingHandler} methods reconstruct state by replaying those events.
 *
 * <p>Axon reconstructs an aggregate instance by:
 * <ol>
 *   <li>Calling the no-arg constructor.</li>
 *   <li>Replaying all persisted domain events in order via the {@code @EventSourcingHandler} methods.</li>
 * </ol>
 */
@Aggregate
@Slf4j
public class OrderAggregate {

    @AggregateIdentifier
    private String orderId;

    private String customerId;
    private OrderStatus status;
    private List<OrderItem> items;
    private BigDecimal totalAmount;
    private String shippingAddress;
    private String trackingNumber;
    private String carrier;

    /** Required by Axon for reconstitution via event replay. */
    protected OrderAggregate() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Command Handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Factory command handler — constructs a new Order aggregate.
     * Axon uses this constructor when it receives a {@link CreateOrderCommand}.
     */
    @CommandHandler
    public OrderAggregate(CreateOrderCommand command) {
        log.info("Handling CreateOrderCommand for orderId={}", command.orderId());

        BigDecimal total = command.items().stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        AggregateLifecycle.apply(new OrderCreatedEvent(
                command.orderId(),
                command.customerId(),
                Collections.unmodifiableList(command.items()),
                total,
                command.shippingAddress(),
                Instant.now()
        ));
    }

    @CommandHandler
    public void handle(ConfirmOrderCommand command) {
        log.info("Handling ConfirmOrderCommand for orderId={}", command.orderId());
        requireStatus(OrderStatus.PENDING, "confirm");

        AggregateLifecycle.apply(new OrderConfirmedEvent(orderId, Instant.now()));
    }

    @CommandHandler
    public void handle(CancelOrderCommand command) {
        log.info("Handling CancelOrderCommand for orderId={}, reason={}", command.orderId(), command.reason());
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            throw new IllegalStateException(
                    "Cannot cancel order %s in status %s".formatted(orderId, status));
        }
        if (status == OrderStatus.CANCELLED) {
            log.warn("Order {} is already cancelled — ignoring duplicate CancelOrderCommand", orderId);
            return;
        }

        AggregateLifecycle.apply(new OrderCancelledEvent(orderId, command.reason(), Instant.now()));
    }

    @CommandHandler
    public void handle(ShipOrderCommand command) {
        log.info("Handling ShipOrderCommand for orderId={}", command.orderId());
        requireStatus(OrderStatus.CONFIRMED, "ship");

        AggregateLifecycle.apply(new OrderShippedEvent(
                orderId,
                command.trackingNumber(),
                command.carrier(),
                Instant.now()
        ));
    }

    @CommandHandler
    public void handle(DeliverOrderCommand command) {
        log.info("Handling DeliverOrderCommand for orderId={}", command.orderId());
        requireStatus(OrderStatus.SHIPPED, "deliver");

        AggregateLifecycle.apply(new OrderDeliveredEvent(orderId, Instant.now()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event Sourcing Handlers — state reconstruction
    // ─────────────────────────────────────────────────────────────────────────

    @EventSourcingHandler
    public void on(OrderCreatedEvent event) {
        this.orderId = event.orderId();
        this.customerId = event.customerId();
        this.items = event.items();
        this.totalAmount = event.totalAmount();
        this.shippingAddress = event.shippingAddress();
        this.status = OrderStatus.PENDING;
    }

    @EventSourcingHandler
    public void on(OrderConfirmedEvent event) {
        this.status = OrderStatus.CONFIRMED;
    }

    @EventSourcingHandler
    public void on(OrderCancelledEvent event) {
        this.status = OrderStatus.CANCELLED;
    }

    @EventSourcingHandler
    public void on(OrderShippedEvent event) {
        this.status = OrderStatus.SHIPPED;
        this.trackingNumber = event.trackingNumber();
        this.carrier = event.carrier();
    }

    @EventSourcingHandler
    public void on(OrderDeliveredEvent event) {
        this.status = OrderStatus.DELIVERED;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void requireStatus(OrderStatus expected, String operation) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Cannot %s order %s: current status is %s, expected %s"
                            .formatted(operation, orderId, status, expected));
        }
    }
}
