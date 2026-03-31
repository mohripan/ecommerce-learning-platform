package com.ecommerce.orderservice.query;

/** Query to retrieve a single order by its identifier. */
public record FindOrderQuery(String orderId) {}
