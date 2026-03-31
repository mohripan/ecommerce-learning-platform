package com.ecommerce.orderservice.saga.command;

import org.axonframework.modelling.command.TargetAggregateIdentifier;

import java.math.BigDecimal;

/**
 * Command dispatched by the OrderSaga to the Payment Service
 * asking it to process payment for the order.
 */
public record ProcessPaymentCommand(

        @TargetAggregateIdentifier
        String paymentId,

        String orderId,
        String customerId,
        BigDecimal amount
) {}
