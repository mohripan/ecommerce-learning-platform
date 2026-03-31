package com.ecommerce.orderservice.query;

import com.ecommerce.orderservice.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderSummaryRepository extends JpaRepository<OrderSummary, String> {

    Optional<OrderSummary> findByOrderId(String orderId);

    Page<OrderSummary> findByCustomerId(String customerId, Pageable pageable);

    Page<OrderSummary> findByStatus(OrderStatus status, Pageable pageable);

    Page<OrderSummary> findByCustomerIdAndStatus(String customerId, OrderStatus status, Pageable pageable);
}
