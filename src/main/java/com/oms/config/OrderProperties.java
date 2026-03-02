package com.oms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.order")
public record OrderProperties(
        int maxItemsPerOrder,
        String defaultStatus
) {
    public OrderProperties {
        if (maxItemsPerOrder <= 0) {
            maxItemsPerOrder = 50;
        }
        if (defaultStatus == null || defaultStatus.isBlank()) {
            defaultStatus = "PENDING";
        }
    }
}
