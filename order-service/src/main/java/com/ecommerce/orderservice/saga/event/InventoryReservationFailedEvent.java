package com.ecommerce.orderservice.saga.event;

/**
 * Published by the Inventory Service when stock reservation fails
 * (e.g. insufficient quantity).  Triggers the cancellation compensating flow.
 */
public record InventoryReservationFailedEvent(

        String inventoryId,
        String orderId,
        String reason
) {}
