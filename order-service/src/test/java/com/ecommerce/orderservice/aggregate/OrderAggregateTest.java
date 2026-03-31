package com.ecommerce.orderservice.aggregate;

import com.ecommerce.orderservice.command.*;
import com.ecommerce.orderservice.event.*;
import com.ecommerce.orderservice.model.OrderItem;
import com.ecommerce.orderservice.model.OrderStatus;
import org.axonframework.test.aggregate.AggregateTestFixture;
import org.axonframework.test.aggregate.FixtureConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Unit tests for {@link OrderAggregate} using Axon's {@link AggregateTestFixture}.
 *
 * <p>Follows the Axon given/when/then pattern:
 * <ul>
 *   <li><b>given</b> — past events that have already occurred (state setup).</li>
 *   <li><b>when</b>  — the command under test.</li>
 *   <li><b>then</b>  — the expected resulting event(s) or exception.</li>
 * </ul>
 */
class OrderAggregateTest {

    private FixtureConfiguration<OrderAggregate> fixture;

    private static final String ORDER_ID = "order-123";
    private static final String CUSTOMER_ID = "cust-456";
    private static final List<OrderItem> ITEMS = List.of(
            new OrderItem("prod-1", "Widget", 2, new BigDecimal("9.99")));

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(OrderAggregate.class);
    }

    @Test
    @DisplayName("CreateOrderCommand emits OrderCreatedEvent with correct total")
    void createOrder_emitsOrderCreatedEvent() {
        fixture.givenNoPriorActivity()
                .when(new CreateOrderCommand(ORDER_ID, CUSTOMER_ID, ITEMS, "123 Main St"))
                .expectSuccessfulHandlerExecution()
                .expectEventsMatching(payloads -> {
                    if (payloads.isEmpty()) return false;
                    Object first = payloads.get(0);
                    return first instanceof OrderCreatedEvent e
                            && ORDER_ID.equals(e.orderId())
                            && CUSTOMER_ID.equals(e.customerId())
                            && new BigDecimal("19.98").compareTo(e.totalAmount()) == 0;
                });
    }

    @Test
    @DisplayName("ConfirmOrderCommand on PENDING order emits OrderConfirmedEvent")
    void confirmOrder_whenPending_emitsOrderConfirmedEvent() {
        fixture.given(new OrderCreatedEvent(ORDER_ID, CUSTOMER_ID, ITEMS,
                        new BigDecimal("19.98"), "123 Main St", Instant.now()))
                .when(new ConfirmOrderCommand(ORDER_ID))
                .expectSuccessfulHandlerExecution()
                .expectEventsMatching(payloads ->
                        !payloads.isEmpty() && payloads.get(0) instanceof OrderConfirmedEvent e
                                && ORDER_ID.equals(e.orderId()));
    }

    @Test
    @DisplayName("ConfirmOrderCommand on CONFIRMED order throws IllegalStateException")
    void confirmOrder_whenAlreadyConfirmed_throwsException() {
        fixture.given(
                        new OrderCreatedEvent(ORDER_ID, CUSTOMER_ID, ITEMS,
                                new BigDecimal("19.98"), "123 Main St", Instant.now()),
                        new OrderConfirmedEvent(ORDER_ID, Instant.now()))
                .when(new ConfirmOrderCommand(ORDER_ID))
                .expectException(IllegalStateException.class);
    }

    @Test
    @DisplayName("CancelOrderCommand on PENDING order emits OrderCancelledEvent")
    void cancelOrder_whenPending_emitsOrderCancelledEvent() {
        fixture.given(new OrderCreatedEvent(ORDER_ID, CUSTOMER_ID, ITEMS,
                        new BigDecimal("19.98"), "123 Main St", Instant.now()))
                .when(new CancelOrderCommand(ORDER_ID, "Customer requested"))
                .expectSuccessfulHandlerExecution()
                .expectEventsMatching(payloads ->
                        !payloads.isEmpty() && payloads.get(0) instanceof OrderCancelledEvent e
                                && "Customer requested".equals(e.reason()));
    }

    @Test
    @DisplayName("CancelOrderCommand on SHIPPED order throws IllegalStateException")
    void cancelOrder_whenShipped_throwsException() {
        fixture.given(
                        new OrderCreatedEvent(ORDER_ID, CUSTOMER_ID, ITEMS,
                                new BigDecimal("19.98"), "123 Main St", Instant.now()),
                        new OrderConfirmedEvent(ORDER_ID, Instant.now()),
                        new OrderShippedEvent(ORDER_ID, "TRK-001", "FedEx", Instant.now()))
                .when(new CancelOrderCommand(ORDER_ID, "Too late"))
                .expectException(IllegalStateException.class);
    }

    @Test
    @DisplayName("Full happy-path: PENDING → CONFIRMED → SHIPPED → DELIVERED")
    void fullLifecycle_happyPath() {
        fixture.given(
                        new OrderCreatedEvent(ORDER_ID, CUSTOMER_ID, ITEMS,
                                new BigDecimal("19.98"), "123 Main St", Instant.now()),
                        new OrderConfirmedEvent(ORDER_ID, Instant.now()),
                        new OrderShippedEvent(ORDER_ID, "TRK-001", "UPS", Instant.now()))
                .when(new DeliverOrderCommand(ORDER_ID))
                .expectSuccessfulHandlerExecution()
                .expectEventsMatching(payloads ->
                        !payloads.isEmpty() && payloads.get(0) instanceof OrderDeliveredEvent);
    }

    @Test
    @DisplayName("ShipOrderCommand on PENDING order throws IllegalStateException")
    void shipOrder_whenNotConfirmed_throwsException() {
        fixture.given(new OrderCreatedEvent(ORDER_ID, CUSTOMER_ID, ITEMS,
                        new BigDecimal("19.98"), "123 Main St", Instant.now()))
                .when(new ShipOrderCommand(ORDER_ID, "TRK-001", "DHL"))
                .expectException(IllegalStateException.class);
    }
}
