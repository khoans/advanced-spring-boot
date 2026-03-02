package com.oms.controller;

import com.oms.dto.OrderEventResponse;
import com.oms.service.OrderEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class OrderEventController {

    private final OrderEventService orderEventService;

    @GetMapping("/api/orders/{orderId}/events")
    public List<OrderEventResponse> getEventsForOrder(@PathVariable Long orderId) {
        return orderEventService.getEventsForOrder(orderId);
    }

    @GetMapping("/api/events/recent")
    public List<OrderEventResponse> getRecentEvents(@RequestParam Instant since) {
        return orderEventService.getRecentEvents(since);
    }
}
