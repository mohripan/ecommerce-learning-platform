package com.ecommerce.orderservice.event;

import java.time.Instant;

/** Emitted when an order transitions from CONFIRMED → SHIPPED. */
public record OrderShippedEvent(

        String orderId,
        String trackingNumber,
        String carrier,
        Instant shippedAt
) {}
