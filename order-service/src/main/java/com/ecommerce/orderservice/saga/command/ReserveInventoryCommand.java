package com.ecommerce.orderservice.saga.command;

import org.axonframework.modelling.command.TargetAggregateIdentifier;

import java.util.List;

/**
 * Command dispatched by the OrderSaga to the Inventory Service
 * asking it to reserve stock for all items in the order.
 */
public record ReserveInventoryCommand(

        @TargetAggregateIdentifier
        String inventoryId,

        String orderId,
        String customerId,
        List<ReservationItem> items
) {
    public record ReservationItem(String productId, int quantity) {}
}
