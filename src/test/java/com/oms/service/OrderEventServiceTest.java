package com.oms.service;

import com.oms.dto.OrderEventResponse;
import com.oms.entity.Customer;
import com.oms.entity.Order;
import com.oms.entity.OrderItem;
import com.oms.entity.OrderStatus;
import com.oms.entity.Product;
import com.oms.mongo.document.OrderEvent;
import com.oms.mongo.document.OrderEventType;
import com.oms.mongo.document.OrderItemSnapshot;
import com.oms.mongo.document.OrderSnapshot;
import com.oms.mongo.repository.OrderEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEventServiceTest {

    @Mock
    private OrderEventRepository orderEventRepository;

    @InjectMocks
    private OrderEventService orderEventService;

    private Order order;

    @BeforeEach
    void setUp() {
        Customer customer = Customer.builder().name("John Doe").email("john@example.com").build();
        customer.setId(1L);

        Product product = Product.builder()
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

        OrderItem item = OrderItem.builder()
                .product(product)
                .quantity(1)
                .unitPrice(new BigDecimal("29.99"))
                .subtotal(new BigDecimal("29.99"))
                .build();
        order.addItem(item);
    }

    @Test
    void recordOrderCreated_shouldSaveCreatedEvent() {
        when(orderEventRepository.save(any(OrderEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        orderEventService.recordOrderCreated(order);

        ArgumentCaptor<OrderEvent> captor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(orderEventRepository).save(captor.capture());

        OrderEvent saved = captor.getValue();
        assertThat(saved.getOrderId()).isEqualTo(1L);
        assertThat(saved.getCustomerId()).isEqualTo(1L);
        assertThat(saved.getEventType()).isEqualTo(OrderEventType.CREATED);
        assertThat(saved.getDescription()).contains("1 item(s)");
        assertThat(saved.getOrderSnapshot().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getOrderSnapshot().items()).hasSize(1);
    }

    @Test
    void recordStatusChange_shouldSaveStatusChangedEvent() {
        when(orderEventRepository.save(any(OrderEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        orderEventService.recordStatusChange(order, OrderStatus.PENDING, OrderStatus.CONFIRMED);

        ArgumentCaptor<OrderEvent> captor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(orderEventRepository).save(captor.capture());

        OrderEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(OrderEventType.STATUS_CHANGED);
        assertThat(saved.getDescription()).contains("PENDING").contains("CONFIRMED");
        assertThat(saved.getMetadata()).containsEntry("fromStatus", "PENDING");
        assertThat(saved.getMetadata()).containsEntry("toStatus", "CONFIRMED");
    }

    @Test
    void recordStatusChange_shouldUseCancelledType_whenCancelling() {
        when(orderEventRepository.save(any(OrderEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        orderEventService.recordStatusChange(order, OrderStatus.PENDING, OrderStatus.CANCELLED);

        ArgumentCaptor<OrderEvent> captor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(orderEventRepository).save(captor.capture());

        assertThat(captor.getValue().getEventType()).isEqualTo(OrderEventType.CANCELLED);
    }

    @Test
    void getEventsForOrder_shouldReturnMappedResponses() {
        OrderEvent event = OrderEvent.builder()
                .id("abc123")
                .orderId(1L)
                .customerId(1L)
                .eventType(OrderEventType.CREATED)
                .description("Order created")
                .metadata(Map.of("test", true))
                .timestamp(Instant.now())
                .build();

        when(orderEventRepository.findByOrderIdOrderByTimestampDesc(1L))
                .thenReturn(List.of(event));

        List<OrderEventResponse> responses = orderEventService.getEventsForOrder(1L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo("abc123");
        assertThat(responses.get(0).eventType()).isEqualTo(OrderEventType.CREATED);
    }

    @Test
    void getRecentEvents_shouldReturnMappedResponses() {
        Instant since = Instant.now().minusSeconds(3600);
        OrderEvent event = OrderEvent.builder()
                .id("def456")
                .orderId(2L)
                .customerId(1L)
                .eventType(OrderEventType.STATUS_CHANGED)
                .description("Status changed")
                .timestamp(Instant.now())
                .build();

        when(orderEventRepository.findByTimestampAfterOrderByTimestampDesc(since))
                .thenReturn(List.of(event));

        List<OrderEventResponse> responses = orderEventService.getRecentEvents(since);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).orderId()).isEqualTo(2L);
    }
}
