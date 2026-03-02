package com.oms.mongo.repository;

import com.oms.mongo.document.OrderEvent;
import com.oms.mongo.document.OrderEventType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface OrderEventRepository extends MongoRepository<OrderEvent, String> {

    List<OrderEvent> findByOrderIdOrderByTimestampDesc(Long orderId);

    List<OrderEvent> findByTimestampAfterOrderByTimestampDesc(Instant since);

    List<OrderEvent> findByEventType(OrderEventType eventType);

    long countByOrderId(Long orderId);
}
