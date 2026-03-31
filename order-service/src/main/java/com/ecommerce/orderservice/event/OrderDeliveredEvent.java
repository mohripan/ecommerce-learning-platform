package com.ecommerce.orderservice.event;

import java.time.Instant;

/** Emitted when a shipment has been delivered to the customer (SHIPPED → DELIVERED). */
public record OrderDeliveredEvent(

        String orderId,
        Instant deliveredAt
) {}
