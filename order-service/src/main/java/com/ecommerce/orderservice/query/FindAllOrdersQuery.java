package com.ecommerce.orderservice.query;

import com.ecommerce.orderservice.model.OrderStatus;

/**
 * Query to list orders with optional filtering by customer and/or status.
 * Pagination parameters follow the convention used throughout the platform.
 */
public record FindAllOrdersQuery(
        String customerId,        // nullable — filter by customer
        OrderStatus status,       // nullable — filter by status
        int page,
        int size
) {
    /** Default constructor — no filter, first page of 20. */
    public FindAllOrdersQuery() {
        this(null, null, 0, 20);
    }
}
