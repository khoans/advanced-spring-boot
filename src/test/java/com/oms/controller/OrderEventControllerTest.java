package com.oms.controller;

import com.oms.dto.OrderEventResponse;
import com.oms.mongo.document.OrderEventType;
import com.oms.service.OrderEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer test for OrderEventController.
 * Tests HTTP routing, request parsing, and JSON response serialization.
 */
@WebMvcTest(OrderEventController.class)
class OrderEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderEventService orderEventService;

    @Test
    void getEventsForOrder_shouldReturn200WithEvents() throws Exception {
        OrderEventResponse event = new OrderEventResponse(
                "abc123", 1L, 1L, OrderEventType.CREATED,
                "Order created with 2 item(s)", Map.of(), Instant.parse("2024-06-01T12:00:00Z"));

        when(orderEventService.getEventsForOrder(1L)).thenReturn(List.of(event));

        mockMvc.perform(get("/api/orders/1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("abc123"))
                .andExpect(jsonPath("$[0].orderId").value(1))
                .andExpect(jsonPath("$[0].eventType").value("CREATED"));
    }

    @Test
    void getEventsForOrder_shouldReturnEmptyListWhenNoEvents() throws Exception {
        when(orderEventService.getEventsForOrder(99L)).thenReturn(List.of());

        mockMvc.perform(get("/api/orders/99/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getRecentEvents_shouldReturn200WithEvents() throws Exception {
        OrderEventResponse event = new OrderEventResponse(
                "def456", 2L, 1L, OrderEventType.STATUS_CHANGED,
                "Status changed from PENDING to CONFIRMED", Map.of("fromStatus", "PENDING"),
                Instant.parse("2024-06-01T14:00:00Z"));

        when(orderEventService.getRecentEvents(any(Instant.class))).thenReturn(List.of(event));

        mockMvc.perform(get("/api/events/recent")
                        .param("since", "2024-06-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("STATUS_CHANGED"));
    }
}
