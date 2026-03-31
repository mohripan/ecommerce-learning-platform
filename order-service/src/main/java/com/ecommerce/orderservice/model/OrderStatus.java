package com.ecommerce.orderservice.model;

/**
 * Lifecycle states for an Order aggregate.
 *
 * <pre>
 *                ┌─────────┐
 *          ──────► PENDING │
 *                └────┬────┘
 *           ┌─────────┴──────────┐
 *           ▼                    ▼
 *      ┌──────────┐        ┌───────────┐
 *      │CONFIRMED │        │ CANCELLED │
 *      └────┬─────┘        └───────────┘
 *           │
 *     ┌─────▼────┐
 *     │ SHIPPED  │
 *     └─────┬────┘
 *           │
 *     ┌─────▼─────┐
 *     │ DELIVERED │
 *     └───────────┘
 * </pre>
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
