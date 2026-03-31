package com.ecommerce.orderservice.api.dto;

import com.ecommerce.orderservice.model.OrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Request body for POST /orders. */
public record CreateOrderRequest(

        @NotBlank
        String customerId,

        @NotEmpty
        @Valid
        List<OrderItem> items,

        @NotBlank
        String shippingAddress
) {}
