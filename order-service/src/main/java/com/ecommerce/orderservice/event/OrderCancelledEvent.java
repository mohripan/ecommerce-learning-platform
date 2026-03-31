package com.ecommerce.orderservice.event;

import java.time.Instant;

/**
 * Emitted when an order is cancelled.
 * The {@code reason} provides an audit trail for why the order was cancelled.
 */
public record OrderCancelledEvent(

        String orderId,
        String reason,
        Instant cancelledAt
) {}
