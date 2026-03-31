package com.ecommerce.orderservice.saga.command;

import org.axonframework.modelling.command.TargetAggregateIdentifier;

/**
 * Compensating command — instructs the Payment Service to refund or void
 * a payment that was already processed when an order must be cancelled.
 */
public record CancelPaymentCommand(

        @TargetAggregateIdentifier
        String paymentId,

        String orderId,
        String reason
) {}
