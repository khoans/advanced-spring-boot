package com.oms.repository;

import com.oms.entity.Order;
import com.oms.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    // Derived query
    List<Order> findByStatus(OrderStatus status);

    // Derived query
    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    // JPQL query — orders above a certain total amount
    @Query("SELECT o FROM Order o WHERE o.totalAmount > :amount ORDER BY o.totalAmount DESC")
    List<Order> findOrdersAboveAmount(@Param("amount") BigDecimal amount);

    // Native query — count orders grouped by status
    @Query(value = "SELECT status, COUNT(*) as order_count FROM customer_order GROUP BY status",
            nativeQuery = true)
    List<Object[]> countOrdersByStatus();
}
