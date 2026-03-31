package com.ecommerce.orderservice.saga;

import com.ecommerce.orderservice.command.CancelOrderCommand;
import com.ecommerce.orderservice.command.ConfirmOrderCommand;
import com.ecommerce.orderservice.event.OrderCreatedEvent;
import com.ecommerce.orderservice.saga.command.*;
import com.ecommerce.orderservice.saga.event.*;
import lombok.extern.slf4j.Slf4j;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.spring.stereotype.Saga;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * OrderSaga — orchestrates the distributed transaction across the
 * Order, Inventory, and Payment services using the Saga pattern.
 *
 * <h2>Happy-path flow</h2>
 * <pre>
 *  OrderCreated ──► ReserveInventory ──► InventoryReserved
 *                                             │
 *                                        ProcessPayment ──► PaymentProcessed
 *                                                                 │
 *                                                          ConfirmOrder (end)
 * </pre>
 *
 * <h2>Compensating flow</h2>
 * <pre>
 *  InventoryReservationFailed ──► CancelOrder (end)
 *
 *  PaymentFailed ──► CancelInventoryReservation ──► CancelOrder (end)
 * </pre>
 *
 * <p>The saga is persisted in the {@code saga_entry} table via {@link org.axonframework.modelling.saga.repository.jpa.JpaSagaStore}.
 * All saga instances are correlated via the {@code orderId} association property.
 */
@Saga
@Slf4j
public class OrderSaga {

    /** Injected by Axon; not serialised as part of saga state. */
    @Autowired
    private transient CommandGateway commandGateway;

    // Saga state — serialised and stored between steps
    private String orderId;
    private String customerId;
    private String inventoryId;
    private String paymentId;
    private java.math.BigDecimal totalAmount;

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1 — Order placed → reserve inventory
    // ─────────────────────────────────────────────────────────────────────────

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void on(OrderCreatedEvent event) {
        log.info("[Saga] OrderCreated — orderId={}, customerId={}", event.orderId(), event.customerId());

        this.orderId = event.orderId();
        this.customerId = event.customerId();
        this.totalAmount = event.totalAmount();
        this.inventoryId = UUID.randomUUID().toString();

        // Associate this saga instance with the inventoryId so we can correlate
        // InventoryReservedEvent / InventoryReservationFailedEvent back to it.
        SagaLifecycle.associateWith("inventoryId", inventoryId);

        var items = event.items().stream()
                .map(i -> new ReserveInventoryCommand.ReservationItem(i.productId(), i.quantity()))
                .toList();

        commandGateway.send(new ReserveInventoryCommand(inventoryId, orderId, customerId, items));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2 — Inventory reserved → process payment
    // ─────────────────────────────────────────────────────────────────────────

    @SagaEventHandler(associationProperty = "inventoryId")
    public void on(InventoryReservedEvent event) {
        log.info("[Saga] InventoryReserved — orderId={}, inventoryId={}", orderId, event.inventoryId());

        this.paymentId = UUID.randomUUID().toString();
        SagaLifecycle.associateWith("paymentId", paymentId);

        commandGateway.send(new ProcessPaymentCommand(paymentId, orderId, customerId, totalAmount));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3 (happy path) — Payment processed → confirm order
    // ─────────────────────────────────────────────────────────────────────────

    @EndSaga
    @SagaEventHandler(associationProperty = "paymentId")
    public void on(PaymentProcessedEvent event) {
        log.info("[Saga] PaymentProcessed — orderId={}, paymentId={}", orderId, event.paymentId());
        commandGateway.sendAndWait(new ConfirmOrderCommand(orderId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compensating flows
    // ─────────────────────────────────────────────────────────────────────────

    /** Inventory reservation failed — cancel the order immediately. */
    @EndSaga
    @SagaEventHandler(associationProperty = "inventoryId")
    public void on(InventoryReservationFailedEvent event) {
        log.warn("[Saga] InventoryReservationFailed — orderId={}, reason={}", orderId, event.reason());
        commandGateway.send(new CancelOrderCommand(orderId,
                "Inventory reservation failed: " + event.reason()));
    }

    /** Payment failed — release inventory reservation, then cancel the order. */
    @EndSaga
    @SagaEventHandler(associationProperty = "paymentId")
    public void on(PaymentFailedEvent event) {
        log.warn("[Saga] PaymentFailed — orderId={}, reason={}", orderId, event.reason());

        commandGateway.send(new CancelInventoryReservationCommand(inventoryId, orderId,
                "Payment failed: " + event.reason()));
        commandGateway.send(new CancelOrderCommand(orderId,
                "Payment failed: " + event.reason()));
    }
}
