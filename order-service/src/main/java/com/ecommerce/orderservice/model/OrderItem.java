package com.ecommerce.orderservice.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Immutable value object representing a single line item within an order.
 */
public record OrderItem(

        @NotBlank
        String productId,

        @NotBlank
        String productName,

        @Min(1)
        int quantity,

        @NotNull @Positive
        BigDecimal unitPrice
) {
    /** Convenience accessor for the computed line total. */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
