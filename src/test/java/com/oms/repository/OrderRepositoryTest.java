package com.oms.repository;

import com.oms.entity.Customer;
import com.oms.entity.Order;
import com.oms.entity.OrderItem;
import com.oms.entity.OrderStatus;
import com.oms.entity.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the OrderRepository using Testcontainers PostgreSQL.
 *
 * @DataJpaTest configures a minimal Spring context with only JPA-related beans:
 *  - Scans and registers @Entity classes
 *  - Flyway runs migrations against the Testcontainers PostgreSQL instance
 *  - Provides a TestEntityManager for bypassing the repository when arranging test data
 *  - Wraps each test in a transaction that rolls back after the test (clean slate per test)
 *  - Does NOT load @Service, @Controller, or other non-JPA beans
 *
 * @AutoConfigureTestDatabase(replace = NONE) prevents Spring from replacing the datasource
 * with an embedded database — we want the Testcontainers PostgreSQL instead.
 *
 * We test four types of repository queries:
 *  1. Derived queries  — Spring Data generates SQL from the method name (findByStatus, findByCustomerId)
 *  2. JPQL @Query       — custom HQL written in the repository (findOrdersAboveAmount)
 *  3. Native @Query     — raw SQL for complex aggregations (countOrdersByStatus)
 *  4. Specifications    — dynamic, composable WHERE-clause filters (OrderSpecification)
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * TestEntityManager is provided by @DataJpaTest. It wraps the JPA EntityManager
     * and is used to persist test fixtures directly — bypassing the repository under test
     * so our arrange step doesn't depend on the code we're verifying.
     */
    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    private Customer customer;
    private Product product;

    @BeforeEach
    void setUp() {
        // Arrange shared fixtures: one customer and one product reused across all tests.
        // We persist via entityManager (not through a repository) to keep arrangement independent.
        customer = Customer.builder()
                .name("John Doe")
                .email("john@example.com")
                .build();
        entityManager.persist(customer);

        product = Product.builder()
                .name("Widget")
                .description("A test widget")
                .price(new BigDecimal("29.99"))
                .stockQuantity(100)
                .build();
        entityManager.persist(product);

        // flush() forces SQL INSERT to execute now, so the data is visible to subsequent queries
        entityManager.flush();
    }

    /**
     * Helper to create and persist an Order with one OrderItem.
     * Using the helper keeps each test method focused on the query being verified.
     */
    private Order createOrder(OrderStatus status, BigDecimal totalAmount) {
        Order order = Order.builder()
                .customer(customer)
                .status(status)
                .totalAmount(totalAmount)
                .build();

        OrderItem item = OrderItem.builder()
                .product(product)
                .quantity(1)
                .unitPrice(product.getPrice())
                .subtotal(product.getPrice())
                .build();
        order.addItem(item);

        return entityManager.persist(order);
    }

    // ---- Derived query tests ----

    @Test
    void findByStatus_shouldReturnMatchingOrders() {
        // Arrange: 2 PENDING + 1 CONFIRMED
        createOrder(OrderStatus.PENDING, new BigDecimal("29.99"));
        createOrder(OrderStatus.CONFIRMED, new BigDecimal("59.98"));
        createOrder(OrderStatus.PENDING, new BigDecimal("89.97"));
        entityManager.flush();

        // Act: derived query — Spring generates "SELECT ... WHERE status = ?"
        List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.PENDING);

        // Assert: only the 2 PENDING orders are returned
        assertThat(pendingOrders).hasSize(2);
        assertThat(pendingOrders).allMatch(o -> o.getStatus() == OrderStatus.PENDING);
    }

    @Test
    void findByCustomerId_shouldReturnPagedResults() {
        // Arrange: 2 orders for the same customer
        createOrder(OrderStatus.PENDING, new BigDecimal("29.99"));
        createOrder(OrderStatus.CONFIRMED, new BigDecimal("59.98"));
        entityManager.flush();

        // Act: derived query with Pageable — verifies pagination metadata is correct
        Page<Order> page = orderRepository.findByCustomerId(customer.getId(), PageRequest.of(0, 10));

        // Assert: page contains both orders; totalElements reflects the full count
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    // ---- JPQL @Query test ----

    @Test
    void findOrdersAboveAmount_shouldReturnOrdersAboveThreshold() {
        // Arrange: orders at $25, $100, $200
        createOrder(OrderStatus.PENDING, new BigDecimal("25.00"));
        createOrder(OrderStatus.CONFIRMED, new BigDecimal("100.00"));
        createOrder(OrderStatus.SHIPPED, new BigDecimal("200.00"));
        entityManager.flush();

        // Act: JPQL query "WHERE o.totalAmount > :amount ORDER BY o.totalAmount DESC"
        List<Order> expensiveOrders = orderRepository.findOrdersAboveAmount(new BigDecimal("50.00"));

        // Assert: $25 order excluded; results sorted descending ($200 before $100)
        assertThat(expensiveOrders).hasSize(2);
        assertThat(expensiveOrders.get(0).getTotalAmount()).isGreaterThan(expensiveOrders.get(1).getTotalAmount());
    }

    // ---- Native SQL @Query test ----

    @Test
    void countOrdersByStatus_shouldReturnCountPerStatus() {
        // Arrange: 2 PENDING + 1 CONFIRMED
        createOrder(OrderStatus.PENDING, new BigDecimal("29.99"));
        createOrder(OrderStatus.PENDING, new BigDecimal("39.99"));
        createOrder(OrderStatus.CONFIRMED, new BigDecimal("59.98"));
        entityManager.flush();

        // Act: native SQL "SELECT status, COUNT(*) FROM customer_order GROUP BY status"
        // Returns List<Object[]> because native queries aren't mapped to entities
        List<Object[]> counts = orderRepository.countOrdersByStatus();

        // Assert: at least one row returned (we have 2 distinct statuses)
        assertThat(counts).isNotEmpty();
    }

    // ---- JPA Specification tests (dynamic query composition) ----

    @Test
    void specification_hasStatus_shouldFilterByStatus() {
        // Arrange: 1 PENDING + 1 CONFIRMED
        createOrder(OrderStatus.PENDING, new BigDecimal("29.99"));
        createOrder(OrderStatus.CONFIRMED, new BigDecimal("59.98"));
        entityManager.flush();

        // Act: single Specification — adds "WHERE status = PENDING" dynamically
        Specification<Order> spec = OrderSpecification.hasStatus(OrderStatus.PENDING);
        List<Order> result = orderRepository.findAll(spec);

        // Assert: only the PENDING order is returned
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void specification_totalAmountRange_shouldFilterByAmount() {
        // Arrange: orders at $25, $100, $200
        createOrder(OrderStatus.PENDING, new BigDecimal("25.00"));
        createOrder(OrderStatus.PENDING, new BigDecimal("100.00"));
        createOrder(OrderStatus.PENDING, new BigDecimal("200.00"));
        entityManager.flush();

        // Act: compose two Specifications with AND — "WHERE amount >= 50 AND amount <= 150"
        Specification<Order> spec = Specification
                .where(OrderSpecification.totalAmountGreaterThan(new BigDecimal("50.00")))
                .and(OrderSpecification.totalAmountLessThan(new BigDecimal("150.00")));

        List<Order> result = orderRepository.findAll(spec);

        // Assert: only the $100 order falls within the range
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void specification_combinedFilters_shouldApplyAll() {
        // Arrange: PENDING/$100, CONFIRMED/$100, PENDING/$25
        createOrder(OrderStatus.PENDING, new BigDecimal("100.00"));
        createOrder(OrderStatus.CONFIRMED, new BigDecimal("100.00"));
        createOrder(OrderStatus.PENDING, new BigDecimal("25.00"));
        entityManager.flush();

        // Act: combine status + amount filters and use paginated findAll
        // This mirrors the real searchOrders() use case in OrderService
        Specification<Order> spec = Specification
                .where(OrderSpecification.hasStatus(OrderStatus.PENDING))
                .and(OrderSpecification.totalAmountGreaterThan(new BigDecimal("50.00")));

        Page<Order> result = orderRepository.findAll(spec, PageRequest.of(0, 10));

        // Assert: only the PENDING/$100 order matches both criteria
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.getContent().get(0).getTotalAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
    }
}
