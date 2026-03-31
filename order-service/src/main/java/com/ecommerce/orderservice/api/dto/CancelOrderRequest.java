package com.ecommerce.orderservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for DELETE/PUT /orders/{id}/cancel. */
public record CancelOrderRequest(@NotBlank String reason) {}
