package com.ecommerce.orderservice.saga.event;

/**
 * Published by the Payment Service when payment processing fails
 * (e.g. card declined).  Triggers the cancellation compensating flow.
 */
public record PaymentFailedEvent(

        String paymentId,
        String orderId,
        String reason
) {}
