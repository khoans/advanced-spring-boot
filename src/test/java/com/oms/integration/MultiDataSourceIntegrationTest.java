package com.oms.integration;

import com.oms.dto.OrderEventResponse;
import com.oms.dto.OrderItemRequest;
import com.oms.dto.OrderRequest;
import com.oms.dto.OrderResponse;
import com.oms.entity.Customer;
import com.oms.entity.OrderStatus;
import com.oms.entity.Product;
import com.oms.repository.CustomerRepository;
import com.oms.repository.ProductRepository;
import com.oms.service.OrderEventService;
import com.oms.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full integration test that verifies cross-database coordination between
 * JPA (PostgreSQL) and MongoDB.
 *
 * Uses Testcontainers to spin up real PostgreSQL and MongoDB instances.
 * Flyway runs migrations against PostgreSQL automatically.
 */
@SpringBootTest
@Testcontainers
class MultiDataSourceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderEventService orderEventService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductRepository productRepository;

    private Customer customer;
    private Product product;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(
                Customer.builder().name("Integration Test User").email("int-" + System.nanoTime() + "@test.com").build());
        product = productRepository.save(
                Product.builder().name("Test Widget").price(new BigDecimal("19.99")).stockQuantity(100).build());
    }

    @Test
    void createOrder_shouldWriteToBothJpaAndMongoDB() {
        OrderRequest request = new OrderRequest(customer.getId(),
                List.of(new OrderItemRequest(product.getId(), 2)));

        OrderResponse response = orderService.createOrder(request);

        // JPA order is persisted
        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);

        // MongoDB event is recorded
        List<OrderEventResponse> events = orderEventService.getEventsForOrder(response.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo(com.oms.mongo.document.OrderEventType.CREATED);
    }

    @Test
    void updateOrderStatus_shouldRecordEventInMongoDB() {
        OrderRequest request = new OrderRequest(customer.getId(),
                List.of(new OrderItemRequest(product.getId(), 1)));
        OrderResponse created = orderService.createOrder(request);

        orderService.updateOrderStatus(created.id(), OrderStatus.CONFIRMED);

        List<OrderEventResponse> events = orderEventService.getEventsForOrder(created.id());
        assertThat(events).hasSize(2);
        // Most recent first (reverse chronological)
        assertThat(events.get(0).eventType()).isEqualTo(com.oms.mongo.document.OrderEventType.STATUS_CHANGED);
        assertThat(events.get(1).eventType()).isEqualTo(com.oms.mongo.document.OrderEventType.CREATED);
    }

    @Test
    void eventsForOrder_shouldBeInReverseChronologicalOrder() {
        OrderRequest request = new OrderRequest(customer.getId(),
                List.of(new OrderItemRequest(product.getId(), 1)));
        OrderResponse created = orderService.createOrder(request);

        orderService.updateOrderStatus(created.id(), OrderStatus.CONFIRMED);
        orderService.updateOrderStatus(created.id(), OrderStatus.SHIPPED);

        List<OrderEventResponse> events = orderEventService.getEventsForOrder(created.id());
        assertThat(events).hasSize(3);

        // Verify reverse chronological ordering
        for (int i = 0; i < events.size() - 1; i++) {
            assertThat(events.get(i).timestamp())
                    .isAfterOrEqualTo(events.get(i + 1).timestamp());
        }
    }

    @Test
    void getRecentEvents_shouldReturnEventsAcrossOrders() {
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        // Create two separate orders
        OrderRequest request1 = new OrderRequest(customer.getId(),
                List.of(new OrderItemRequest(product.getId(), 1)));
        OrderRequest request2 = new OrderRequest(customer.getId(),
                List.of(new OrderItemRequest(product.getId(), 1)));

        orderService.createOrder(request1);
        orderService.createOrder(request2);

        List<OrderEventResponse> recentEvents = orderEventService.getRecentEvents(before);
        assertThat(recentEvents).hasSizeGreaterThanOrEqualTo(2);
    }
}
