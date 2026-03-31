package com.ecommerce.orderservice.command;

import jakarta.validation.constraints.NotBlank;
import org.axonframework.modelling.command.TargetAggregateIdentifier;

/** Marks a SHIPPED order as delivered to the customer. */
public record DeliverOrderCommand(

        @TargetAggregateIdentifier
        @NotBlank
        String orderId
) {}
