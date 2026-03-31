package com.ecommerce.orderservice.saga.command;

import org.axonframework.modelling.command.TargetAggregateIdentifier;

/**
 * Compensating command — instructs the Inventory Service to release
 * a previously reserved stock when an order must be cancelled.
 */
public record CancelInventoryReservationCommand(

        @TargetAggregateIdentifier
        String inventoryId,

        String orderId,
        String reason
) {}
