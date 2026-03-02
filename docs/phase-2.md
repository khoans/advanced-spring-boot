# Phase 2 — Multiple Data Sources (JPA + MongoDB)

## What Was Implemented

Phase 2 adds **MongoDB** as a second data source alongside the existing PostgreSQL/JPA stack. An **Order Event/Audit Log** — an append-only event stream — records every order lifecycle change in MongoDB.

### Architecture Overview

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  Controller   │    │  Controller   │    │  Controller   │
│  (Order)      │    │  (Customer)   │    │  (Events)     │
└──────┬───────┘    └──────┬───────┘    └──────┬───────┘
       │                   │                   │
       ▼                   ▼                   ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ OrderService  │───▶│OrderEventSvc │    │OrderEventSvc │
└──────┬───────┘    └──────┬───────┘    └──────┬───────┘
       │                   │                   │
       ▼                   ▼                   ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   JPA/PG     │    │   MongoDB    │    │   MongoDB    │
│  (Orders)    │    │  (Events)    │    │  (Events)    │
└──────────────┘    └──────────────┘    └──────────────┘
```

### Key Components

| Component | Purpose |
|-----------|---------|
| `OrderEvent` (MongoDB document) | Stores order lifecycle events with embedded snapshots |
| `OrderEventRepository` | Spring Data MongoDB repository for event queries |
| `OrderEventService` | Records events and provides query methods |
| `OrderEventController` | REST endpoints for event retrieval |
| `RoutingDataSource` | Read/write routing via `AbstractRoutingDataSource` |
| `@ReadOnly` annotation | Routes annotated methods to read replica |
| Flyway migrations | V1-V3 SQL scripts for PostgreSQL schema |

## Architecture Decisions

### 1. Dev Profile: PostgreSQL via Docker (not H2)
Dev and prod now both use PostgreSQL. This eliminates H2-specific SQL compatibility issues and ensures Flyway scripts work identically in both environments.

### 2. Cross-Database Coordination: Best-Effort Pattern
JPA (PostgreSQL) is the source of truth. MongoDB writes are best-effort:
```java
Order saved = orderRepository.save(order);  // JPA transaction
try {
    orderEventService.recordOrderCreated(saved);  // MongoDB (outside JPA tx)
} catch (Exception e) {
    log.error("Failed to record event: {}", e.getMessage());
    // JPA order is NOT rolled back
}
```
This avoids distributed transaction complexity while ensuring the primary data store is always consistent.

### 3. MongoDB for Events (not JPA)
- Append-only, immutable events with flexible schema
- Each event type has different payload fields (natural fit for documents)
- Embedded order snapshots (denormalized) — efficient reads without joins
- Independent scaling from the relational store

### 4. Read/Write Routing
`AbstractRoutingDataSource` + custom `@ReadOnly` annotation + AOP aspect. Activated via `oms.routing.enabled=true`. Off in dev (single PostgreSQL), on in prod (primary + replica).

### 5. Flyway for Schema Migrations
Pure PostgreSQL DDL. Both dev and prod use `ddl-auto: validate` — Flyway manages the schema, Hibernate only validates it matches the entity model.

## How to Run

### Prerequisites
- Docker Desktop running
- Java 21
- Maven

### Start Infrastructure
```bash
docker-compose up -d
```
This starts:
- PostgreSQL (primary) on port 5432
- PostgreSQL (replica) on port 5433
- MongoDB on port 27017

### Run the Application
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Run Tests
```bash
mvn test
```
Tests use Testcontainers — they spin up their own PostgreSQL and MongoDB instances automatically. Docker must be running.

## Verification

### 1. Create an order
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": 1, "items": [{"productId": 1, "quantity": 2}]}'
```

### 2. Check events for that order
```bash
curl http://localhost:8080/api/orders/1/events
```

### 3. Update order status
```bash
curl -X PATCH "http://localhost:8080/api/orders/1/status?status=CONFIRMED"
```

### 4. Check events again (should now have 2)
```bash
curl http://localhost:8080/api/orders/1/events
```

### 5. Get recent events
```bash
curl "http://localhost:8080/api/events/recent?since=2024-01-01T00:00:00Z"
```

## New Files Created

| Category | Files |
|----------|-------|
| MongoDB documents (4) | `OrderEvent`, `OrderEventType`, `OrderSnapshot`, `OrderItemSnapshot` |
| MongoDB repository (1) | `OrderEventRepository` |
| Config (2) | `MongoConfig`, `DataSourceRoutingConfig` |
| Routing (5) | `DataSourceType`, `DataSourceContextHolder`, `RoutingDataSource`, `ReadOnly`, `ReadOnlyDataSourceAspect` |
| Service (1) | `OrderEventService` |
| DTO (1) | `OrderEventResponse` |
| Controller (1) | `OrderEventController` |
| Flyway (3) | `V1__create_customer_table.sql`, `V2__create_product_table.sql`, `V3__create_order_and_items_tables.sql` |
| Docker (1) | `docker-compose.yml` |
| Tests (4) | `OrderEventRepositoryTest`, `OrderEventServiceTest`, `OrderEventControllerTest`, `MultiDataSourceIntegrationTest` |

## Modified Files

| File | Change |
|------|--------|
| `pom.xml` | Added MongoDB, AOP, Flyway, Testcontainers deps; removed H2 |
| `application.yml` | Added MongoDB database name, routing config |
| `application-dev.yml` | Switched from H2 to PostgreSQL + MongoDB |
| `application-prod.yml` | Added MongoDB URI, routing datasource config |
| `OrderService.java` | Added cross-DB event publishing |
| `OrderServiceTest.java` | Added @Mock for OrderEventService |
| `OmsApplicationTest.java` | Added Testcontainers for PostgreSQL + MongoDB |
| `OrderRepositoryTest.java` | Migrated from H2 to Testcontainers PostgreSQL |
