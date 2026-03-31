package com.ecommerce.orderservice.command;

import jakarta.validation.constraints.NotBlank;
import org.axonframework.modelling.command.TargetAggregateIdentifier;

/**
 * Cancels an order that has not yet been shipped.
 * Can be triggered by the saga on payment/inventory failure or explicitly by a customer.
 */
public record CancelOrderCommand(

        @TargetAggregateIdentifier
        @NotBlank
        String orderId,

        @NotBlank
        String reason
) {}
