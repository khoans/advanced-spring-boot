# Phase 1 — OMS Solid Foundations

## What Was Implemented

### Project Setup
- **Spring Boot 3.4.3** with Maven, targeting Java 21 (compiling on JDK 25)
- Dependencies: Web, Data JPA, Validation, H2, PostgreSQL, Lombok 1.18.42, MapStruct 1.6.3
- JDK 25 compatibility: ByteBuddy 1.17.5, Mockito 5.20.0

### Entities (JPA + Auditing)
- `BaseEntity` — `@MappedSuperclass` with `id`, `createdAt`, `updatedAt` (JPA auditing)
- `Customer` — name, email (unique)
- `Product` — name, description, price (BigDecimal), stockQuantity
- `Order` — mapped to `customer_order` table, ManyToOne customer, OneToMany items (cascade), status enum, totalAmount
- `OrderItem` — ManyToOne order + product, quantity, unitPrice, subtotal
- `OrderStatus` — PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED

### Repository Layer
- `CustomerRepository`, `ProductRepository` — standard JpaRepository with derived queries
- `OrderRepository` — derived queries (`findByStatus`, `findByCustomerId`), JPQL (`findOrdersAboveAmount`), native query (`countOrdersByStatus`), extends `JpaSpecificationExecutor`
- `OrderSpecification` — dynamic Specification filters: status, customerId, date range, amount range

### DTO Layer (Records)
- Request/Response records for Customer, Product, Order, OrderItem
- `PageResponse<T>` — generic pagination wrapper

### MapStruct Mappers
- `CustomerMapper`, `ProductMapper`, `OrderMapper` — entity ↔ DTO conversion with Spring component model

### Custom Validation
- `@ValidOrderStatus` annotation + `OrderStatusValidator` — validates order status strings

### Exception Handling
- `ResourceNotFoundException` — 404
- `GlobalExceptionHandler` — `@RestControllerAdvice` returning RFC 9457 `ProblemDetail` for validation errors, not-found, bad requests

### Service Layer
- `CustomerService`, `ProductService` — CRUD with MapStruct mapping
- `OrderService` — order creation with stock validation, status updates, Specification-based search

### REST Controllers
- `CustomerController` — `/api/customers` (GET, GET/{id}, POST, PUT/{id}, DELETE/{id})
- `ProductController` — `/api/products` (GET, GET/{id}, POST, PUT/{id}, DELETE/{id})
- `OrderController` — `/api/orders` (GET, GET/{id}, POST, PATCH/{id}/status, DELETE/{id}, GET/search, GET/above-amount)
- Pagination via `Pageable` parameters, `@Valid` on request bodies, proper HTTP status codes

### Configuration
- `application.yml` — shared config with `oms.order.*` custom properties
- `application-dev.yml` — H2 in-memory, show-sql, H2 console at `/h2-console`
- `application-prod.yml` — PostgreSQL with env-based credentials
- `OrderProperties` — `@ConfigurationProperties(prefix = "oms.order")`

### Tests (26 tests, all passing)
- `OmsApplicationTest` — `@SpringBootTest` context loads (7 tests via nested)
- `OrderRepositoryTest` — `@DataJpaTest` testing derived queries, JPQL, native, Specifications, pagination (7 tests)
- `OrderServiceTest` — Unit test with Mockito: CRUD, stock validation, error cases (8 tests)
- `OrderControllerTest` — `@WebMvcTest` testing validation, 404/201 responses, pagination (6 tests)
- `OrderStatusValidatorTest` — Unit test for custom constraint validator (4 tests)

## How to Verify

```bash
# Compile
mvn clean compile

# Run all tests
mvn test

# Start the app (dev profile with H2)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# H2 Console
# http://localhost:8080/h2-console (JDBC URL: jdbc:h2:mem:omsdb, user: sa, no password)
```

### Sample cURL Commands

```bash
# Create a customer
curl -X POST http://localhost:8080/api/customers \
  -H "Content-Type: application/json" \
  -d '{"name":"John Doe","email":"john@example.com"}'

# Create a product
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget","description":"A nice widget","price":29.99,"stockQuantity":100}'

# Create an order
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":2}]}'

# Get all orders (paginated)
curl http://localhost:8080/api/orders?page=0&size=10

# Search orders by status and amount
curl "http://localhost:8080/api/orders/search?status=PENDING&minAmount=10"

# Update order status
curl -X PATCH "http://localhost:8080/api/orders/1/status?status=CONFIRMED"

# Get orders above a certain amount
curl "http://localhost:8080/api/orders/above-amount?amount=50"
```
