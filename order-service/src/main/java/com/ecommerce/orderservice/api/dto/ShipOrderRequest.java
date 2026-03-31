package com.ecommerce.orderservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for PUT /orders/{id}/ship. */
public record ShipOrderRequest(
        @NotBlank String trackingNumber,
        @NotBlank String carrier
) {}
