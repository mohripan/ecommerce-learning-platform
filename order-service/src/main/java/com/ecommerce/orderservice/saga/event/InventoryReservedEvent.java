package com.ecommerce.orderservice.saga.event;

/**
 * Published by the Inventory Service when stock has been successfully reserved
 * for all items in an order.
 */
public record InventoryReservedEvent(

        String inventoryId,
        String orderId
) {}
