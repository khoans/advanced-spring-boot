package com.oms.service;

import com.oms.dto.OrderItemRequest;
import com.oms.dto.OrderRequest;
import com.oms.dto.OrderResponse;
import com.oms.dto.PageResponse;
import com.oms.entity.Customer;
import com.oms.entity.Order;
import com.oms.entity.OrderItem;
import com.oms.entity.OrderStatus;
import com.oms.entity.Product;
import com.oms.exception.ResourceNotFoundException;
import com.oms.mapper.OrderMapper;
import com.oms.repository.CustomerRepository;
import com.oms.repository.OrderRepository;
import com.oms.repository.OrderSpecification;
import com.oms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final OrderMapper orderMapper;

    public PageResponse<OrderResponse> getAllOrders(Pageable pageable) {
        Page<OrderResponse> page = orderRepository.findAll(pageable)
                .map(orderMapper::toResponse);
        return PageResponse.of(page);
    }

    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", request.customerId()));

        Order order = Order.builder()
                .customer(customer)
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.items()) {
            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemRequest.productId()));

            if (product.getStockQuantity() < itemRequest.quantity()) {
                throw new IllegalArgumentException(
                        "Insufficient stock for product: " + product.getName()
                                + ". Available: " + product.getStockQuantity()
                                + ", Requested: " + itemRequest.quantity());
            }

            BigDecimal unitPrice = product.getPrice();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(itemRequest.quantity()));

            OrderItem item = OrderItem.builder()
                    .product(product)
                    .quantity(itemRequest.quantity())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build();

            order.addItem(item);
            totalAmount = totalAmount.add(subtotal);

            product.setStockQuantity(product.getStockQuantity() - itemRequest.quantity());
            productRepository.save(product);
        }

        order.setTotalAmount(totalAmount);
        Order saved = orderRepository.save(order);
        return orderMapper.toResponse(saved);
    }

    @Transactional
    public OrderResponse updateOrderStatus(Long id, OrderStatus status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
        order.setStatus(status);
        Order saved = orderRepository.save(order);
        return orderMapper.toResponse(saved);
    }

    @Transactional
    public void deleteOrder(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new ResourceNotFoundException("Order", id);
        }
        orderRepository.deleteById(id);
    }

    public PageResponse<OrderResponse> searchOrders(OrderStatus status, Long customerId,
                                                     BigDecimal minAmount, BigDecimal maxAmount,
                                                     Pageable pageable) {
        Specification<Order> spec = Specification.where(OrderSpecification.hasStatus(status))
                .and(OrderSpecification.hasCustomerId(customerId))
                .and(OrderSpecification.totalAmountGreaterThan(minAmount))
                .and(OrderSpecification.totalAmountLessThan(maxAmount));

        Page<OrderResponse> page = orderRepository.findAll(spec, pageable)
                .map(orderMapper::toResponse);
        return PageResponse.of(page);
    }

    public List<OrderResponse> getOrdersAboveAmount(BigDecimal amount) {
        return orderRepository.findOrdersAboveAmount(amount).stream()
                .map(orderMapper::toResponse)
                .toList();
    }
}
