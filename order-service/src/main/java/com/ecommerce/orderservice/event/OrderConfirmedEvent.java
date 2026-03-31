package com.ecommerce.orderservice.event;

import java.time.Instant;

/**
 * Emitted when an order transitions from PENDING → CONFIRMED after
 * successful inventory reservation and payment processing.
 */
public record OrderConfirmedEvent(

        String orderId,
        Instant confirmedAt
) {}
