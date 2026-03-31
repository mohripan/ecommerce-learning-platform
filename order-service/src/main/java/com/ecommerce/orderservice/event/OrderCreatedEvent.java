package com.ecommerce.orderservice.event;

import com.ecommerce.orderservice.model.OrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Emitted when a new order has been placed by a customer.
 * This event is persisted in the event store and published to the
 * {@code order-events} Kafka topic for downstream consumers.
 */
public record OrderCreatedEvent(

        String orderId,
        String customerId,
        List<OrderItem> items,
        BigDecimal totalAmount,
        String shippingAddress,
        Instant createdAt
) {}
