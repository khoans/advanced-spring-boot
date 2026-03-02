package com.oms.dto;

import com.oms.mongo.document.OrderEventType;

import java.time.Instant;
import java.util.Map;

public record OrderEventResponse(
        String id,
        Long orderId,
        Long customerId,
        OrderEventType eventType,
        String description,
        Map<String, Object> metadata,
        Instant timestamp
) {}
