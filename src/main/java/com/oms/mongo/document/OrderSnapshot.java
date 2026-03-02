package com.oms.mongo.document;

import com.oms.entity.OrderStatus;

import java.math.BigDecimal;
import java.util.List;

public record OrderSnapshot(
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemSnapshot> items
) {}
