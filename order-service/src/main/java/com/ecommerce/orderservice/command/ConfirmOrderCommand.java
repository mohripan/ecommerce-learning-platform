package com.ecommerce.orderservice.command;

import jakarta.validation.constraints.NotBlank;
import org.axonframework.modelling.command.TargetAggregateIdentifier;

/**
 * Confirms a PENDING order after inventory and payment have been validated
 * by the {@link com.ecommerce.orderservice.saga.OrderSaga}.
 */
public record ConfirmOrderCommand(

        @TargetAggregateIdentifier
        @NotBlank
        String orderId
) {}
