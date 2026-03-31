package com.ecommerce.orderservice.saga.event;

/**
 * Published by the Payment Service when payment has been successfully processed.
 */
public record PaymentProcessedEvent(

        String paymentId,
        String orderId
) {}
