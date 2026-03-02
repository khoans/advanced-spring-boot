package com.oms.mongo.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document("order_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {

    @Id
    private String id;

    @Indexed
    private Long orderId;

    private Long customerId;

    private OrderEventType eventType;

    private String description;

    private OrderSnapshot orderSnapshot;

    private Map<String, Object> metadata;

    @Indexed
    private Instant timestamp;

    @CreatedDate
    private Instant createdAt;
}
