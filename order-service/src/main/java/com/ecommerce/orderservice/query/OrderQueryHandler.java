package com.ecommerce.orderservice.query;

import com.ecommerce.orderservice.event.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.queryhandling.QueryHandler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Dual-purpose component that:
 * <ol>
 *   <li><strong>Projects</strong> domain events into the {@link OrderSummary} read model
 *       (via {@link EventHandler} methods — runs in the {@code order-query-processor} tracking processor).</li>
 *   <li><strong>Answers</strong> queries against the read model
 *       (via {@link QueryHandler} methods).</li>
 * </ol>
 *
 * <p>All projection updates are idempotent: duplicate event delivery (at-least-once
 * Kafka semantics) will not corrupt the read model.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OrderQueryHandler {

    private final OrderSummaryRepository repository;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Projections — keep the read model in sync with domain events
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void on(OrderCreatedEvent event) {
        log.debug("[Projection] OrderCreated — orderId={}", event.orderId());

        String itemsJson = toJson(event.items());

        OrderSummary summary = OrderSummary.builder()
                .orderId(event.orderId())
                .customerId(event.customerId())
                .status(com.ecommerce.orderservice.model.OrderStatus.PENDING)
                .totalAmount(event.totalAmount())
                .shippingAddress(event.shippingAddress())
                .itemsJson(itemsJson)
                .createdAt(event.createdAt())
                .updatedAt(event.createdAt())
                .build();

        repository.save(summary);
    }

    @EventHandler
    public void on(OrderConfirmedEvent event) {
        log.debug("[Projection] OrderConfirmed — orderId={}", event.orderId());
        updateSummary(event.orderId(), summary -> {
            summary.setStatus(com.ecommerce.orderservice.model.OrderStatus.CONFIRMED);
            summary.setUpdatedAt(event.confirmedAt());
        });
    }

    @EventHandler
    public void on(OrderCancelledEvent event) {
        log.debug("[Projection] OrderCancelled — orderId={}", event.orderId());
        updateSummary(event.orderId(), summary -> {
            summary.setStatus(com.ecommerce.orderservice.model.OrderStatus.CANCELLED);
            summary.setUpdatedAt(event.cancelledAt());
        });
    }

    @EventHandler
    public void on(OrderShippedEvent event) {
        log.debug("[Projection] OrderShipped — orderId={}", event.orderId());
        updateSummary(event.orderId(), summary -> {
            summary.setStatus(com.ecommerce.orderservice.model.OrderStatus.SHIPPED);
            summary.setTrackingNumber(event.trackingNumber());
            summary.setCarrier(event.carrier());
            summary.setUpdatedAt(event.shippedAt());
        });
    }

    @EventHandler
    public void on(OrderDeliveredEvent event) {
        log.debug("[Projection] OrderDelivered — orderId={}", event.orderId());
        updateSummary(event.orderId(), summary -> {
            summary.setStatus(com.ecommerce.orderservice.model.OrderStatus.DELIVERED);
            summary.setUpdatedAt(event.deliveredAt());
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query Handlers
    // ─────────────────────────────────────────────────────────────────────────

    @QueryHandler
    public OrderSummary handle(FindOrderQuery query) {
        return repository.findByOrderId(query.orderId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Order not found: " + query.orderId()));
    }

    @QueryHandler
    public List<OrderSummary> handle(FindAllOrdersQuery query) {
        PageRequest pageRequest = PageRequest.of(query.page(), query.size(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<OrderSummary> page;
        if (query.customerId() != null && query.status() != null) {
            page = repository.findByCustomerIdAndStatus(query.customerId(), query.status(), pageRequest);
        } else if (query.customerId() != null) {
            page = repository.findByCustomerId(query.customerId(), pageRequest);
        } else if (query.status() != null) {
            page = repository.findByStatus(query.status(), pageRequest);
        } else {
            page = repository.findAll(pageRequest);
        }
        return page.getContent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updateSummary(String orderId, java.util.function.Consumer<OrderSummary> updater) {
        repository.findByOrderId(orderId).ifPresentOrElse(summary -> {
            updater.accept(summary);
            repository.save(summary);
        }, () -> log.warn("[Projection] OrderSummary not found for orderId={} — skipping update", orderId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise items to JSON", e);
            return "[]";
        }
    }
}
