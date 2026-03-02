package com.oms.mongo.repository;

import com.oms.entity.OrderStatus;
import com.oms.mongo.document.OrderEvent;
import com.oms.mongo.document.OrderEventType;
import com.oms.mongo.document.OrderItemSnapshot;
import com.oms.mongo.document.OrderSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository test for OrderEventRepository using a real MongoDB via Testcontainers.
 *
 * @DataMongoTest loads only the MongoDB slice (Mongo repositories, MongoTemplate, etc.)
 * — no JPA, no web layer, no service beans.
 */
@DataMongoTest
@Testcontainers
class OrderEventRepositoryTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private OrderEventRepository orderEventRepository;

    @BeforeEach
    void setUp() {
        orderEventRepository.deleteAll();
    }

    @Test
    void save_shouldPersistOrderEvent() {
        OrderEvent event = buildEvent(1L, OrderEventType.CREATED, Instant.now());

        OrderEvent saved = orderEventRepository.save(event);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrderId()).isEqualTo(1L);
        assertThat(saved.getEventType()).isEqualTo(OrderEventType.CREATED);
    }

    @Test
    void findByOrderId_shouldReturnEventsInReverseChronologicalOrder() {
        Instant now = Instant.now();
        orderEventRepository.save(buildEvent(1L, OrderEventType.CREATED, now.minus(2, ChronoUnit.HOURS)));
        orderEventRepository.save(buildEvent(1L, OrderEventType.STATUS_CHANGED, now.minus(1, ChronoUnit.HOURS)));
        orderEventRepository.save(buildEvent(1L, OrderEventType.CANCELLED, now));
        // Different order — should not appear
        orderEventRepository.save(buildEvent(2L, OrderEventType.CREATED, now));

        List<OrderEvent> events = orderEventRepository.findByOrderIdOrderByTimestampDesc(1L);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).getEventType()).isEqualTo(OrderEventType.CANCELLED);
        assertThat(events.get(1).getEventType()).isEqualTo(OrderEventType.STATUS_CHANGED);
        assertThat(events.get(2).getEventType()).isEqualTo(OrderEventType.CREATED);
    }

    @Test
    void findByTimestampAfter_shouldReturnRecentEvents() {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);

        orderEventRepository.save(buildEvent(1L, OrderEventType.CREATED, twoHoursAgo));
        orderEventRepository.save(buildEvent(2L, OrderEventType.CREATED, now));

        List<OrderEvent> recent = orderEventRepository.findByTimestampAfterOrderByTimestampDesc(
                oneHourAgo.minus(1, ChronoUnit.MINUTES));

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).getOrderId()).isEqualTo(2L);
    }

    @Test
    void findByEventType_shouldFilterCorrectly() {
        Instant now = Instant.now();
        orderEventRepository.save(buildEvent(1L, OrderEventType.CREATED, now));
        orderEventRepository.save(buildEvent(2L, OrderEventType.STATUS_CHANGED, now));
        orderEventRepository.save(buildEvent(3L, OrderEventType.CREATED, now));

        List<OrderEvent> created = orderEventRepository.findByEventType(OrderEventType.CREATED);

        assertThat(created).hasSize(2);
    }

    @Test
    void countByOrderId_shouldReturnCorrectCount() {
        Instant now = Instant.now();
        orderEventRepository.save(buildEvent(1L, OrderEventType.CREATED, now));
        orderEventRepository.save(buildEvent(1L, OrderEventType.STATUS_CHANGED, now));
        orderEventRepository.save(buildEvent(2L, OrderEventType.CREATED, now));

        assertThat(orderEventRepository.countByOrderId(1L)).isEqualTo(2);
        assertThat(orderEventRepository.countByOrderId(2L)).isEqualTo(1);
        assertThat(orderEventRepository.countByOrderId(99L)).isZero();
    }

    private OrderEvent buildEvent(Long orderId, OrderEventType type, Instant timestamp) {
        OrderSnapshot snapshot = new OrderSnapshot(
                OrderStatus.PENDING,
                new BigDecimal("29.99"),
                List.of(new OrderItemSnapshot(1L, "Widget", 1, new BigDecimal("29.99"))));

        return OrderEvent.builder()
                .orderId(orderId)
                .customerId(1L)
                .eventType(type)
                .description("Test event")
                .orderSnapshot(snapshot)
                .metadata(Map.of("test", true))
                .timestamp(timestamp)
                .build();
    }
}
