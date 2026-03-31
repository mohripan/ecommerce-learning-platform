package com.ecommerce.orderservice.query;

import com.ecommerce.orderservice.model.OrderStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-model / projection for an Order, persisted in the {@code order_summary} table.
 *
 * <p>This entity is updated by the {@link OrderQueryHandler} event handlers and
 * queried directly by the REST layer via the {@link OrderQueryHandler} query handlers.
 * It intentionally contains only the data needed for list/detail views — the canonical
 * event log in the event store remains the source of truth.
 */
@Entity
@Table(name = "order_summary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSummary {

    @Id
    @Column(nullable = false, updatable = false)
    private String orderId;

    @Column(nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String shippingAddress;

    /** JSON representation of the order items stored for convenience. */
    @Column(columnDefinition = "TEXT")
    private String itemsJson;

    private String trackingNumber;
    private String carrier;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
