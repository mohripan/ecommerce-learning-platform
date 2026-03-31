package com.ecommerce.orderservice.command;

import jakarta.validation.constraints.NotBlank;
import org.axonframework.modelling.command.TargetAggregateIdentifier;

/** Marks a CONFIRMED order as shipped and records the carrier tracking number. */
public record ShipOrderCommand(

        @TargetAggregateIdentifier
        @NotBlank
        String orderId,

        @NotBlank
        String trackingNumber,

        @NotBlank
        String carrier
) {}
