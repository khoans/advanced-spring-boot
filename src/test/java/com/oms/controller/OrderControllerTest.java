package com.oms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.config.JwtAuthenticationFilter;
import com.oms.config.JwtService;
import com.oms.dto.OrderItemRequest;
import com.oms.dto.OrderItemResponse;
import com.oms.dto.OrderRequest;
import com.oms.dto.OrderResponse;
import com.oms.dto.PageResponse;
import com.oms.entity.OrderStatus;
import com.oms.exception.GlobalExceptionHandler;
import com.oms.exception.ResourceNotFoundException;
import com.oms.service.OrderService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer integration test for OrderController.
 *
 * @WebMvcTest(OrderController.class) loads ONLY the web slice:
 *  - The OrderController itself
 *  - Spring MVC infrastructure (DispatcherServlet, Jackson, validation, exception handlers)
 *  - Does NOT load @Service, @Repository, or @Component beans — those are replaced by mocks
 *
 * This makes the test fast (no full context) while still exercising:
 *  - Request routing (URL → controller method)
 *  - Request deserialization (JSON → Java object via Jackson)
 *  - Bean Validation (@Valid triggers on request body; invalid input → 400)
 *  - Response serialization (Java object → JSON)
 *  - HTTP status codes (201 Created, 400 Bad Request, 404 Not Found)
 *  - Exception handling (GlobalExceptionHandler converts exceptions to ProblemDetail JSON)
 *
 * @MockitoBean replaces the real OrderService bean with a Mockito mock in the web context.
 * This is the Spring Boot 3.4+ replacement for the deprecated @MockBean.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    /** MockMvc simulates HTTP requests without starting a real server */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson ObjectMapper for serializing request DTOs to JSON */
    @Autowired
    private ObjectMapper objectMapper;

    /** Mock replaces the real OrderService — we control its behavior via when() */
    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setupFilterMock() throws ServletException, IOException {
        // Configure the mock filter to pass requests down the chain
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            HttpServletRequest request = invocation.getArgument(0);
            HttpServletResponse response = invocation.getArgument(1);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class), any(FilterChain.class));
    }

    // ---- POST /api/orders — valid request ----

    @Test
    @WithMockUser(username = "testuser", roles = "CUSTOMER")
    void createOrder_shouldReturn201WhenValid() throws Exception {
        // Arrange: valid request body + stub the service to return a response
        OrderRequest request = new OrderRequest(1L, List.of(new OrderItemRequest(1L, 2)));
        OrderResponse response = new OrderResponse(
                1L, 1L, "John Doe", OrderStatus.PENDING, new BigDecimal("59.98"),
                List.of(new OrderItemResponse(1L, 1L, "Widget", 2, new BigDecimal("29.99"), new BigDecimal("59.98"))),
                LocalDateTime.now(), null
        );

        when(orderService.createOrder(any(OrderRequest.class))).thenReturn(response);

        // Act & Assert: POST returns 201 with the order JSON
        mockMvc.perform(post("/api/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(59.98));
    }

    // ---- POST /api/orders — validation: empty items list ----

    @Test
    @WithMockUser(username = "testuser", roles = "CUSTOMER")
    void createOrder_shouldReturn400WhenNoItems() throws Exception {
        // Arrange: empty items list violates @NotEmpty on OrderRequest.items
        OrderRequest request = new OrderRequest(1L, List.of());

        // Act & Assert: Spring Validation rejects the request before it reaches the service.
        // GlobalExceptionHandler converts MethodArgumentNotValidException → ProblemDetail.
        mockMvc.perform(post("/api/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    // ---- POST /api/orders — validation: null customerId ----

    @Test
    @WithMockUser(username = "testuser", roles = "CUSTOMER")
    void createOrder_shouldReturn400WhenCustomerIdNull() throws Exception {
        // Arrange: null customerId violates @NotNull on OrderRequest.customerId
        // Using raw JSON string to send an explicit null (rather than omitting the field)
        String json = """
                {"customerId": null, "items": [{"productId": 1, "quantity": 1}]}
                """;

        // Act & Assert: validation kicks in → 400 Bad Request
        mockMvc.perform(post("/api/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    // ---- GET /api/orders/{id} — not found ----

    @Test
    @WithMockUser(username = "testuser", roles = "CUSTOMER")
    void getOrderById_shouldReturn404WhenNotFound() throws Exception {
        // Arrange: service throws ResourceNotFoundException (like it would for a missing ID)
        when(orderService.getOrderById(99L)).thenThrow(new ResourceNotFoundException("Order", 99L));

        // Act & Assert: GlobalExceptionHandler catches the exception and returns ProblemDetail
        // with 404 status and "Resource Not Found" title
        mockMvc.perform(get("/api/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    // ---- GET /api/orders/{id} — found ----

    @Test
    @WithMockUser(username = "testuser", roles = "CUSTOMER")
    void getOrderById_shouldReturn200WhenFound() throws Exception {
        // Arrange: service returns a valid order
        OrderResponse response = new OrderResponse(
                1L, 1L, "John Doe", OrderStatus.PENDING, new BigDecimal("29.99"),
                List.of(), LocalDateTime.now(), null
        );

        when(orderService.getOrderById(1L)).thenReturn(response);

        // Act & Assert: 200 OK with correct JSON serialization
        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.customerName").value("John Doe"));
    }

    // ---- GET /api/orders — paginated list ----

    @Test
    @WithMockUser(username = "testuser", roles = "CUSTOMER")
    void getAllOrders_shouldReturnPagedResponse() throws Exception {
        // Arrange: service returns an empty page (tests pagination wrapper serialization)
        PageResponse<OrderResponse> pageResponse = new PageResponse<>(
                List.of(), 0, 20, 0, 0, true
        );

        when(orderService.getAllOrders(any())).thenReturn(pageResponse);

        // Act & Assert: verify the PageResponse structure is serialized correctly
        // (content array, page number, last flag, etc.)
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.last").value(true));
    }
}
