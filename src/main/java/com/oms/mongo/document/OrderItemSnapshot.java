package com.oms.mongo.document;

import java.math.BigDecimal;

public record OrderItemSnapshot(
        Long productId,
        String productName,
        Integer quantity,
        BigDecimal unitPrice
) {}
