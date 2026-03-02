package com.oms.service;

import com.oms.dto.OrderItemRequest;
import com.oms.dto.OrderItemResponse;
import com.oms.dto.OrderRequest;
import com.oms.dto.OrderResponse;
import com.oms.entity.Customer;
import com.oms.entity.Order;
import com.oms.entity.OrderItem;
import com.oms.entity.OrderStatus;
import com.oms.entity.Product;
import com.oms.exception.ResourceNotFoundException;
import com.oms.mapper.OrderMapper;
import com.oms.repository.CustomerRepository;
import com.oms.repository.OrderRepository;
import com.oms.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for OrderService — tests business logic in isolation from the database.
 *
 * @ExtendWith(MockitoExtension.class) enables Mockito annotations without loading Spring.
 * This is a pure unit test: fast, no I/O, no Spring context.
 *
 * Key Mockito annotations used:
 *  - @Mock     — creates a mock (fake) implementation of a dependency
 *  - @InjectMocks — creates the real OrderService and injects all @Mock fields into it
 *
 * Pattern per test:
 *  1. Arrange: configure mock behavior with when(...).thenReturn(...)
 *  2. Act:     call the service method under test
 *  3. Assert:  verify the return value and/or that specific mock methods were called
 *
 * We test both happy paths (order created, status updated, etc.) and error paths
 * (customer not found, insufficient stock, order not found for deletion).
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    // Mocks replace real repository/mapper beans — they return whatever we configure via when()
    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderEventService orderEventService;

    // The real service under test — Mockito injects the 5 mocks above into its constructor
    @InjectMocks
    private OrderService orderService;

    // Shared test fixtures used across multiple tests
    private Customer customer;
    private Product product;
    private Order order;
    private OrderResponse orderResponse;

    @BeforeEach
    void setUp() {
        // Build entity fixtures with IDs set manually (normally JPA generates them)
        customer = Customer.builder().name("John Doe").email("john@example.com").build();
        customer.setId(1L);

        product = Product.builder()
                .name("Widget")
                .price(new BigDecimal("29.99"))
                .stockQuantity(100)
                .build();
        product.setId(1L);

        order = Order.builder()
                .customer(customer)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("29.99"))
                .build();
        order.setId(1L);
        order.setCreatedAt(LocalDateTime.now());

        OrderItem item = OrderItem.builder()
                .product(product)
                .quantity(1)
                .unitPrice(new BigDecimal("29.99"))
                .subtotal(new BigDecimal("29.99"))
                .build();
        item.setId(1L);
        order.addItem(item);

        // Pre-built response DTO — the mapper mock will return this
        orderResponse = new OrderResponse(
                1L, 1L, "John Doe", OrderStatus.PENDING, new BigDecimal("29.99"),
                List.of(new OrderItemResponse(1L, 1L, "Widget", 1, new BigDecimal("29.99"), new BigDecimal("29.99"))),
                order.getCreatedAt(), null
        );
    }

    // ---- Happy path: get order by ID ----

    @Test
    void getOrderById_shouldReturnOrder() {
        // Arrange: repository finds the order, mapper converts it
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderMapper.toResponse(order)).thenReturn(orderResponse);

        // Act
        OrderResponse result = orderService.getOrderById(1L);

        // Assert: correct response + verify repository was called
        assertThat(result).isEqualTo(orderResponse);
        assertThat(result.customerId()).isEqualTo(1L);
        verify(orderRepository).findById(1L);
    }

    // ---- Error path: order not found ----

    @Test
    void getOrderById_shouldThrowWhenNotFound() {
        // Arrange: repository returns empty
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert: service throws ResourceNotFoundException with entity name and ID
        assertThatThrownBy(() -> orderService.getOrderById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order")
                .hasMessageContaining("99");
    }

    // ---- Happy path: create order with stock deduction ----

    @Test
    void createOrder_shouldCreateOrderSuccessfully() {
        // Arrange: customer exists, product exists with enough stock
        OrderRequest request = new OrderRequest(1L, List.of(new OrderItemRequest(1L, 1)));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(productRepository.save(any(Product.class))).thenReturn(product);
        when(orderMapper.toResponse(any(Order.class))).thenReturn(orderResponse);

        // Act
        OrderResponse result = orderService.createOrder(request);

        // Assert: order created with PENDING status, repository.save() was called
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING);
        verify(orderRepository).save(any(Order.class));
    }

    // ---- Error path: customer does not exist ----

    @Test
    void createOrder_shouldThrowWhenCustomerNotFound() {
        OrderRequest request = new OrderRequest(99L, List.of(new OrderItemRequest(1L, 1)));

        // Arrange: customer lookup returns empty
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert: fails before even looking at products
        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");
    }

    // ---- Error path: product out of stock ----

    @Test
    void createOrder_shouldThrowWhenInsufficientStock() {
        // Arrange: product has 0 stock, but we request 5 units
        product.setStockQuantity(0);
        OrderRequest request = new OrderRequest(1L, List.of(new OrderItemRequest(1L, 5)));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        // Act & Assert: business rule violation throws IllegalArgumentException
        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient stock");
    }

    // ---- Happy path: update order status ----

    @Test
    void updateOrderStatus_shouldUpdateStatus() {
        // Arrange: order exists
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(orderMapper.toResponse(any(Order.class))).thenReturn(orderResponse);

        // Act: change status from PENDING to CONFIRMED
        OrderResponse result = orderService.updateOrderStatus(1L, OrderStatus.CONFIRMED);

        // Assert: save() was called (the status mutation happens on the entity in-memory)
        assertThat(result).isNotNull();
        verify(orderRepository).save(any(Order.class));
    }

    // ---- Happy path: delete existing order ----

    @Test
    void deleteOrder_shouldDeleteWhenExists() {
        // Arrange: order exists
        when(orderRepository.existsById(1L)).thenReturn(true);

        // Act
        orderService.deleteOrder(1L);

        // Assert: deleteById was called on the repository
        verify(orderRepository).deleteById(1L);
    }

    // ---- Error path: delete non-existent order ----

    @Test
    void deleteOrder_shouldThrowWhenNotFound() {
        // Arrange: order does not exist
        when(orderRepository.existsById(99L)).thenReturn(false);

        // Act & Assert: throws before attempting deletion
        assertThatThrownBy(() -> orderService.deleteOrder(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
