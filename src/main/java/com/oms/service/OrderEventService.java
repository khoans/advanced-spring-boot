package com.oms.service;

import com.oms.dto.OrderEventResponse;
import com.oms.entity.Order;
import com.oms.entity.OrderStatus;
import com.oms.mongo.document.OrderEvent;
import com.oms.mongo.document.OrderEventType;
import com.oms.mongo.document.OrderItemSnapshot;
import com.oms.mongo.document.OrderSnapshot;
import com.oms.mongo.repository.OrderEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderEventService {

    private final OrderEventRepository orderEventRepository;

    public void recordOrderCreated(Order order) {
        OrderEvent event = OrderEvent.builder()
                .orderId(order.getId())
                .customerId(order.getCustomer().getId())
                .eventType(OrderEventType.CREATED)
                .description("Order created with " + order.getItems().size() + " item(s)")
                .orderSnapshot(buildSnapshot(order))
                .timestamp(Instant.now())
                .build();

        orderEventRepository.save(event);
    }

    public void recordStatusChange(Order order, OrderStatus fromStatus, OrderStatus toStatus) {
        OrderEvent event = OrderEvent.builder()
                .orderId(order.getId())
                .customerId(order.getCustomer().getId())
                .eventType(toStatus == OrderStatus.CANCELLED ? OrderEventType.CANCELLED : OrderEventType.STATUS_CHANGED)
                .description("Status changed from " + fromStatus + " to " + toStatus)
                .orderSnapshot(buildSnapshot(order))
                .metadata(Map.of("fromStatus", fromStatus.name(), "toStatus", toStatus.name()))
                .timestamp(Instant.now())
                .build();

        orderEventRepository.save(event);
    }

    public List<OrderEventResponse> getEventsForOrder(Long orderId) {
        return orderEventRepository.findByOrderIdOrderByTimestampDesc(orderId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<OrderEventResponse> getRecentEvents(Instant since) {
        return orderEventRepository.findByTimestampAfterOrderByTimestampDesc(since).stream()
                .map(this::toResponse)
                .toList();
    }

    private OrderSnapshot buildSnapshot(Order order) {
        List<OrderItemSnapshot> itemSnapshots = order.getItems().stream()
                .map(item -> new OrderItemSnapshot(
                        item.getProduct().getId(),
                        item.getProduct().getName(),
                        item.getQuantity(),
                        item.getUnitPrice()))
                .toList();

        return new OrderSnapshot(order.getStatus(), order.getTotalAmount(), itemSnapshots);
    }

    private OrderEventResponse toResponse(OrderEvent event) {
        return new OrderEventResponse(
                event.getId(),
                event.getOrderId(),
                event.getCustomerId(),
                event.getEventType(),
                event.getDescription(),
                event.getMetadata(),
                event.getTimestamp());
    }
}
