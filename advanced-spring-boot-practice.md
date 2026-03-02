# Advanced Spring Boot Practice — Implementation Plan

> **Scenario:** Build an **Order Management System** (OMS) — a multi-module, production-grade application that evolves through 6 phases, each introducing progressively more advanced Spring Boot concepts.
>
> **Prerequisites assumed:** Java 17+, Maven/Gradle, basic Spring Boot (REST controllers, JPA basics), basic Docker.

---

## Phase 1 — Solid Foundations (Intermediate)

**Goal:** Set up a clean, well-structured Spring Boot project with proper configuration, validation, error handling, and testing.

### 1.1 Project Bootstrap

- Initialize a Spring Boot 3.x project (Spring Initializr or manually).
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `postgresql` (via Docker Compose), `lombok`, `mapstruct`.
- Configure multi-profile setup: `application.yml`, `application-dev.yml`, `application-prod.yml`.
- Use `@ConfigurationProperties` with a custom prefix (e.g., `oms.order.*`) to bind typed configuration.

### 1.2 Domain & Repository Layer

- Entities: `Order`, `OrderItem`, `Product`, `Customer`.
- Use JPA auditing (`@CreatedDate`, `@LastModifiedDate`, `@EntityListeners`).
- Custom Spring Data JPA repository methods: derived queries, `@Query` (JPQL & native), Specifications for dynamic filtering.
- Pagination and sorting via `Pageable`.

### 1.3 Service & Controller Layer

- DTOs with `record` classes; use MapStruct for entity ↔ DTO mapping.
- Bean Validation (`@Valid`, custom constraint annotations like `@ValidOrderStatus`).
- Global exception handling with `@RestControllerAdvice` and RFC 7807 `ProblemDetail`.
- HATEOAS links with `spring-boot-starter-hateoas` (optional stretch).

### 1.4 Testing (Intro)

- Write basic unit tests and slice tests to verify Phase 1 code works.
- Full testing strategy is covered in **Phase 9 — Comprehensive Testing**.

### 1.5 Deliverables Checklist

- [ ] Multi-profile YAML configuration with `@ConfigurationProperties`.
- [ ] CRUD REST API for Orders with validation and error handling.
- [ ] Auditing on entities.
- [ ] Dynamic query with Specifications.
- [ ] Basic unit + slice tests for Phase 1 code.

---

## Phase 2 — Multiple Data Sources: JPA + MongoDB (Intermediate-Advanced)

**Goal:** Configure the OMS to connect to multiple databases simultaneously — JPA (PostgreSQL) for relational data and MongoDB for document data. Learn multi-datasource configuration, cross-database coordination, compensating transactions, read/write routing, and schema migrations.

### 2.1 Business Scenario

The OMS now needs to work with **two data source types**:

| Data Source | Database | Purpose |
|-------------|----------|---------|
| **Primary (orders)** | PostgreSQL (via Docker) | Main OLTP database — orders, customers, products (existing) |
| **Event Store** | MongoDB | Append-only order event/audit log — tracks every order lifecycle change |

This mirrors real projects where different data models justify different storage engines: relational for transactional CRUD, document store for flexible event streams.

**Why MongoDB for events?**
- Events are **append-only and immutable** — high-throughput inserts without relational constraints.
- Each event type has **different payload fields** — flexible schema without catch-all JSON columns or multiple tables.
- Events embed **denormalized snapshots** of order state — natural document model.
- No disruption to existing JPA entities — purely additive.

### 2.2 MongoDB Document Model

```java
@Document(collection = "order_events")
public class OrderEvent {
    @Id
    private String id;                    // MongoDB ObjectId
    private Long orderId;                 // reference to JPA Order.id
    private Long customerId;              // denormalized for querying
    private OrderEventType eventType;     // CREATED, STATUS_CHANGED, CANCELLED
    private String description;           // human-readable
    private OrderSnapshot orderSnapshot;  // embedded snapshot of order state at event time
    private Map<String, Object> metadata; // flexible extra data per event type
    private Instant timestamp;            // when the event occurred
}
```

Key document patterns demonstrated:
- **Embedded documents:** `OrderSnapshot` contains nested `List<OrderItemSnapshot>` — no JOINs needed.
- **Flexible schema:** `metadata` map allows each event type to carry different data without schema changes.
- **Indexed fields:** `orderId` and `timestamp` indexed for efficient querying.

### 2.3 Multi-DataSource Configuration

Spring Boot auto-configures JPA and MongoDB separately when both starters are on the classpath.

**JPA:** Keep auto-configured (existing setup unchanged). The `@Primary` DataSource, EntityManagerFactory, and TransactionManager are all auto-created.

**MongoDB:** Add `spring-boot-starter-data-mongodb`. Auto-configuration creates `MongoClient`, `MongoTemplate`, and scans for `MongoRepository` beans.

```yaml
# application.yml additions
spring:
  data:
    mongodb:
      database: oms_events

# application-dev.yml additions
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/oms_events

# application-prod.yml additions
spring:
  data:
    mongodb:
      uri: mongodb://${MONGO_HOST:localhost}:${MONGO_PORT:27017}/oms_events
```

**MongoDB auditing:** Create `MongoConfig` with `@EnableMongoAuditing` (parallel to existing `JpaAuditingConfig`).

#### Package structure

MongoDB domain lives in a separate `com.oms.mongo` package. Existing JPA code stays untouched.

```
com.oms
├── config/
│   ├── JpaAuditingConfig.java          (existing, unchanged)
│   ├── OrderProperties.java            (existing, unchanged)
│   └── MongoConfig.java                (NEW — @EnableMongoAuditing)
├── mongo/                              (NEW — all MongoDB domain code)
│   ├── document/
│   │   ├── OrderEvent.java             (@Document)
│   │   ├── OrderSnapshot.java          (embedded record)
│   │   ├── OrderItemSnapshot.java      (embedded record)
│   │   └── OrderEventType.java         (enum)
│   └── repository/
│       └── OrderEventRepository.java   (MongoRepository)
├── service/
│   ├── OrderService.java               (MODIFIED — cross-DB event publishing)
│   ├── OrderEventService.java          (NEW — MongoDB CRUD)
│   └── ...existing services unchanged
├── controller/
│   ├── OrderEventController.java       (NEW — event query endpoints)
│   └── ...existing controllers unchanged
└── ...existing packages unchanged
```

### 2.4 Scenarios to Implement

#### Scenario A: Cross-Database Order Event Publishing

**What:** Customer places an order → write Order to JPA → write OrderEvent to MongoDB.
**Challenge:** Two different databases, two different persistence mechanisms — `@Transactional` only covers JPA.
**Pattern:** JPA is the source of truth. MongoDB event write is best-effort with compensating transaction.

```java
@Transactional
public OrderResponse createOrder(OrderRequest request) {
    // ... existing JPA logic ...
    Order saved = orderRepository.save(order);

    // After JPA write, publish event to MongoDB (outside JPA transaction)
    try {
        orderEventService.recordOrderCreated(saved);
    } catch (Exception e) {
        // MongoDB write failed — log for retry/reconciliation
        // Do NOT roll back the JPA order
        log.error("Failed to record event for order {}: {}", saved.getId(), e.getMessage());
    }

    return orderMapper.toResponse(saved);
}
```

**Practice:**
- Implement cross-DB coordination in `OrderService.createOrder()` and `updateOrderStatus()`.
- Handle the case where MongoDB is unreachable (order still succeeds, event is logged for retry).
- Understand why `@Transactional` cannot span both databases.

#### Scenario B: Dynamic DataSource Routing (JPA Read/Write)

**What:** Route JPA reads to a read-replica and writes to the primary, transparently.
**Practice:**
- Implement `AbstractRoutingDataSource` with a `ThreadLocal` to hold the current datasource key.
- Create a custom `@ReadOnly` annotation that sets the routing context.
- AOP aspect switches the data source before method execution.

```java
public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.getDataSourceType();
    }
}

// Usage
@ReadOnly  // routes to read-replica
public List<Order> getRecentOrders() { ... }
```

- Activated via `@ConditionalOnProperty("oms.routing.enabled")` — off by default for dev.
- In dev, both primary and replica point to the same PostgreSQL (routing mechanism still works, but disabled by default via `oms.routing.enabled=false`).
- In prod, replica points to a PostgreSQL read replica (`oms.routing.enabled=true`).

#### Scenario C: Schema Migration with Flyway

**What:** Manage JPA database schema changes with Flyway instead of `ddl-auto`.
**Practice:**
- Create Flyway migration scripts for the existing Customer, Product, Order, OrderItem tables.
- Use `BIGSERIAL` for auto-incrementing IDs (pure PostgreSQL — no H2 compatibility needed since dev uses PostgreSQL via Docker).
- Replace `ddl-auto: create-drop` (dev) with `ddl-auto: validate` + Flyway.
- MongoDB does not need schema migrations — indexes are declared via `@Indexed` annotations.

```sql
-- V1__create_customer_table.sql
CREATE TABLE customer (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP
);
```

### 2.5 Transaction Management Across Data Sources

Understand the tradeoffs:

| Approach | How | When to Use |
|----------|-----|-------------|
| **Separate transactions** | JPA has `@Transactional`, MongoDB writes are independent | Default. Accept eventual consistency. |
| **Compensating transaction** | If MongoDB fails after JPA commits, log for retry | When you can tolerate brief inconsistency but need eventual correction. |
| **`@TransactionalEventListener`** | Publish Spring event in JPA tx, MongoDB write happens after commit | Cleaner separation of concerns, harder to debug. |
| **JTA / Atomikos** | True distributed transaction (2PC) | Only when you MUST have atomicity. Heavy, slow, avoid if possible. |

**Practice:** Implement Scenario A with "separate + compensating" (try/catch pattern). Discuss when you would choose `@TransactionalEventListener` instead.

### 2.6 Testing Multiple Data Sources

#### Slice tests per data source

- **JPA:** `@DataJpaTest` + Testcontainers `PostgreSQLContainer` + `@AutoConfigureTestDatabase(replace = NONE)` — migrated from H2 to real PostgreSQL so Flyway migrations run identically to dev/prod.
- **MongoDB:** `@DataMongoTest` with Testcontainers `MongoDBContainer` — tests MongoDB repository methods.

#### Full integration test with Testcontainers

Spin up **PostgreSQL + MongoDB** containers in the same test:

```java
@SpringBootTest
@Testcontainers
class MultiDataSourceIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired OrderService orderService;
    @Autowired OrderEventRepository orderEventRepository;

    @Test
    void createOrder_writesToBothJpaAndMongoDB() { ... }

    @Test
    void updateOrderStatus_recordsEventInMongoDB() { ... }
}
```

### 2.7 Docker Compose

```yaml
services:
  postgres:
    image: postgres:16
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: omsdb
      POSTGRES_USER: oms
      POSTGRES_PASSWORD: oms

  postgres-replica:
    image: postgres:16
    ports: ["5433:5432"]
    environment:
      POSTGRES_DB: omsdb
      POSTGRES_USER: oms
      POSTGRES_PASSWORD: oms

  mongodb:
    image: mongo:7
    ports: ["27017:27017"]
```

### 2.8 Deliverables Checklist

- [ ] MongoDB data source configuration alongside existing JPA.
- [ ] `OrderEvent` document model with embedded snapshots and flexible metadata.
- [ ] Cross-database order event publishing with compensating transaction pattern.
- [ ] `OrderEventService` and `OrderEventController` for event CRUD and querying.
- [ ] `AbstractRoutingDataSource` with custom `@ReadOnly` annotation and AOP aspect.
- [ ] Flyway schema migrations for JPA tables.
- [ ] `@DataMongoTest` slice tests with Testcontainers.
- [ ] Full integration test with PostgreSQL + MongoDB Testcontainers.
- [ ] Docker Compose with PostgreSQL (primary + replica) and MongoDB.

---

## Phase 3 — Security & Authentication (Advanced)

**Goal:** Secure the API with Spring Security 6 and JWT-based authentication.

### 3.1 Spring Security Configuration

- Security filter chain with `SecurityFilterChain` bean (no `WebSecurityConfigurerAdapter`).
- Stateless session management (`SessionCreationPolicy.STATELESS`).
- CORS and CSRF configuration for a REST API.

### 3.2 JWT Authentication

- Implement `/auth/register` and `/auth/login` endpoints.
- JWT token generation, validation, and refresh token flow.
- Custom `OncePerRequestFilter` to extract and validate JWT from `Authorization` header.
- Store users in DB with `BCryptPasswordEncoder`.

### 3.3 Authorization

- Role-based access: `ROLE_CUSTOMER`, `ROLE_ADMIN`.
- Method-level security with `@PreAuthorize("hasRole('ADMIN')")`.
- Custom `@CurrentUser` annotation using `@AuthenticationPrincipal`.

### 3.4 Testing Security

- Test secured endpoints with `@WithMockUser` and custom `@WithMockJwt`.
- Test that unauthenticated/unauthorized requests return proper 401/403.

### 3.5 Deliverables Checklist

- [ ] JWT login/register flow.
- [ ] Role-based authorization on endpoints.
- [ ] Security slice tests.

---

## Phase 4 — AOP, Async Processing, Caching & Scheduling (Advanced)

**Goal:** Master Aspect-Oriented Programming — the mechanism that powers `@Transactional`, `@Cacheable`, `@Async`, and Spring Security — then use it to build cross-cutting concerns, async processing, caching, and scheduled tasks.

### 4.1 Aspect-Oriented Programming (AOP)

> AOP is not just a "nice to know" — it's the engine behind most Spring magic. Understanding it unlocks your ability to debug `@Transactional` not working, `@Cacheable` being bypassed on self-invocation, and `@Async` silently running synchronously.

#### 4.1.1 Core Concepts

Understand these terms before writing any code:

| Concept | What It Means | OMS Example |
|---------|--------------|-------------|
| **Aspect** | A class that contains cross-cutting logic | `LoggingAspect`, `PerformanceAspect` |
| **Advice** | The action taken (before, after, around) | "Log method entry and exit" |
| **Join Point** | A point during execution (method call) | `OrderService.placeOrder()` |
| **Pointcut** | An expression that selects join points | `execution(* com.oms.order.service.*.*(..))` |
| **Weaving** | How the aspect is applied (Spring uses runtime proxies) | Spring creates a proxy around `OrderService` |

#### 4.1.2 Spring AOP Proxy Mechanism — Why It Matters

**Critical to understand:** Spring AOP works via **JDK dynamic proxies** (interfaces) or **CGLIB proxies** (classes). This has real consequences:

```
Client → [Proxy] → Target Bean
              ↑
         AOP advice runs here
```

**Self-invocation trap** (the #1 AOP gotcha):

```java
@Service
public class OrderService {

    @Cacheable("orders")
    public Order getOrder(Long id) { ... }

    public OrderSummary getOrderSummary(Long id) {
        // THIS BYPASSES THE CACHE — self-invocation skips the proxy!
        Order order = this.getOrder(id);
        return toSummary(order);
    }
}
```

**Practice exercise:** Reproduce the self-invocation bug, then fix it using:
1. Inject `OrderService` into itself (`@Lazy` self-injection).
2. Use `AopContext.currentProxy()`.
3. Extract the cached method into a separate bean (cleanest solution).

#### 4.1.3 Custom Aspects to Implement

##### Aspect A: Method Execution Logging

**What:** Log entry, exit, arguments, return value, and duration for all service methods.
**Learn:** `@Around` advice, `ProceedingJoinPoint`, pointcut expressions.

```java
@Aspect
@Component
@Slf4j
public class LoggingAspect {

    @Around("execution(* com.oms..service.*.*(..))")
    public Object logMethodExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().toShortString();
        log.info("→ {} args={}", method, joinPoint.getArgs());

        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            log.info("← {} returned in {}ms", method, elapsed);
            return result;
        } catch (Throwable ex) {
            log.error("✖ {} threw {}", method, ex.getClass().getSimpleName());
            throw ex;
        }
    }
}
```

##### Aspect B: Performance Monitoring with Micrometer

**What:** Automatically record `Timer` metrics for every controller endpoint.
**Learn:** Combining AOP with observability, custom annotations.

```java
@Aspect
@Component
public class PerformanceAspect {

    private final MeterRegistry registry;

    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object timeEndpoint(ProceedingJoinPoint joinPoint) throws Throwable {
        Timer.Sample sample = Timer.start(registry);
        try {
            return joinPoint.proceed();
        } finally {
            sample.stop(Timer.builder("http.custom.request.duration")
                .tag("method", joinPoint.getSignature().getName())
                .register(registry));
        }
    }
}
```

##### Aspect C: Audit Trail

**What:** Automatically record WHO did WHAT and WHEN for sensitive operations.
**Learn:** Custom annotation + AOP, accessing `SecurityContext` from an aspect.

```java
// Custom annotation
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String action();
}

// Usage
@Auditable(action = "CANCEL_ORDER")
public void cancelOrder(Long orderId) { ... }

// Aspect
@Aspect
@Component
public class AuditAspect {

    @Autowired AuditLogRepository auditRepo;

    @AfterReturning("@annotation(auditable)")
    public void audit(JoinPoint joinPoint, Auditable auditable) {
        String user = SecurityContextHolder.getContext()
            .getAuthentication().getName();
        auditRepo.save(new AuditLog(
            user, auditable.action(),
            Arrays.toString(joinPoint.getArgs()),
            Instant.now()
        ));
    }
}
```

##### Aspect D: Retry with Custom Annotation

**What:** Build a lightweight `@Retryable` from scratch before using Resilience4j.
**Learn:** Deep `@Around` advice, exception handling, understanding what Resilience4j does under the hood.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Retryable {
    int maxAttempts() default 3;
    long delayMs() default 1000;
    Class<? extends Throwable>[] retryOn() default {Exception.class};
}

@Aspect
@Component
public class RetryAspect {

    @Around("@annotation(retryable)")
    public Object retry(ProceedingJoinPoint joinPoint, Retryable retryable) throws Throwable {
        Throwable lastException = null;
        for (int attempt = 1; attempt <= retryable.maxAttempts(); attempt++) {
            try {
                return joinPoint.proceed();
            } catch (Throwable ex) {
                if (Arrays.stream(retryable.retryOn())
                        .noneMatch(cls -> cls.isInstance(ex))) {
                    throw ex; // not retryable
                }
                lastException = ex;
                if (attempt < retryable.maxAttempts()) {
                    Thread.sleep(retryable.delayMs());
                }
            }
        }
        throw lastException;
    }
}
```

##### Aspect E: Rate Limiting per User

**What:** Limit API calls per user using AOP + Caffeine (before introducing Redis-based solutions).
**Learn:** Stateful aspects, combining AOP with caching primitives.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {
    int maxRequests() default 10;
    int windowSeconds() default 60;
}

// Aspect uses Caffeine to track request counts per user per method
```

#### 4.1.4 Pointcut Expression Cheat Sheet

Practice writing these — they're the "SQL" of AOP:

| Expression | Matches |
|-----------|---------|
| `execution(* com.oms..service.*.*(..))` | Any method in any service class |
| `execution(public * *(..))` | Any public method |
| `@within(RestController)` | Any method in a class annotated with `@RestController` |
| `@annotation(Auditable)` | Any method annotated with `@Auditable` |
| `within(com.oms.order..*)` | Any method within the `order` package |
| `bean(*Service)` | Any method on beans whose name ends with "Service" |
| `execution(* com.oms..*.*(..)) && @annotation(Transactional)` | Transactional methods in OMS |

#### 4.1.5 AOP Ordering & Gotchas

- **Aspect ordering:** Use `@Order(1)` to control which aspect runs first when multiple aspects match the same method. Lower = higher priority.
- **Ordering scenario:** `LoggingAspect(@Order(1))` → `PerformanceAspect(@Order(2))` → `AuditAspect(@Order(3))`. The logging wraps everything.
- **Proxy visibility:** AOP only intercepts calls that go THROUGH the proxy. Private methods, final classes, and self-invocations are invisible.
- **`@Transactional` interaction:** If your custom aspect runs OUTSIDE the transaction proxy, it won't see uncommitted data. Use `@Order` to place it inside.

#### 4.1.6 Testing AOP

| What to Test | How |
|-------------|-----|
| Aspect is triggered | `@SpringBootTest` + verify side effect (audit log saved, metric recorded) |
| Pointcut matches correctly | Write a test with matching and non-matching methods, assert aspect fires only on expected ones |
| Self-invocation bypass | Demonstrate the bug in a test, then fix and re-test |
| Aspect ordering | Inject a `List<String>` log, verify execution order |
| Custom `@Retryable` | Mock service to throw twice then succeed, verify 3 calls made |
| Rate limiting aspect | Fire N+1 requests, assert the last one is rejected |

```java
@SpringBootTest
class AuditAspectIntegrationTest {
    @Autowired OrderService orderService;
    @Autowired AuditLogRepository auditRepo;

    @Test
    void cancelOrder_createsAuditLogEntry() {
        orderService.cancelOrder(1L);

        AuditLog log = auditRepo.findTopByOrderByCreatedAtDesc();
        assertThat(log.getAction()).isEqualTo("CANCEL_ORDER");
        assertThat(log.getUser()).isEqualTo("testuser");
    }
}
```

#### 4.1.7 Deliverables Checklist (AOP)

- [ ] `LoggingAspect` — log entry/exit/duration for all service methods.
- [ ] `PerformanceAspect` — record Micrometer `Timer` for controller endpoints.
- [ ] `AuditAspect` with `@Auditable` custom annotation — audit trail for sensitive operations.
- [ ] `RetryAspect` with `@Retryable` custom annotation — retry with configurable attempts/delay.
- [ ] `RateLimitAspect` with `@RateLimited` — per-user rate limiting using Caffeine.
- [ ] Self-invocation demo: reproduce the bug and fix it.
- [ ] Aspect ordering demo: verify `@Order` controls execution sequence.
- [ ] Integration tests for each aspect.

---

### 4.2 Asynchronous Processing

- Enable `@Async` with a custom `TaskExecutor` bean (thread pool tuning).
- Use `CompletableFuture` return types in async service methods.
- Handle async exceptions with `AsyncUncaughtExceptionHandler`.
- **Now you understand why** `@Async` only works through the proxy — you learned this in 4.1.

### 4.3 Caching — L1, L2 & Two-Tier Strategy

Understand the **three levels of caching** and when each applies:

| Level | Technology | Scope | Speed | Shared Across Instances? |
|-------|-----------|-------|-------|--------------------------|
| **JPA 1st-level cache** | Hibernate persistence context | Per-transaction | Fastest | No (per EntityManager) |
| **L1 — Local cache** | Caffeine | Per-JVM (in-process) | ~nanoseconds | No |
| **L2 — Distributed cache** | Redis | Cluster-wide | ~1–5ms network | Yes |

#### 4.3.1 JPA First-Level Cache (Persistence Context)

The Hibernate persistence context is the "invisible" cache that most developers don't think about.

**Scenarios to understand it:**
- Load the same `Order` entity twice within the same `@Transactional` method → Hibernate returns the **same object reference** (no second SQL query).
- Load an `Order`, modify it, then call `findById()` again → you get the **modified** version from the persistence context, NOT the DB.
- Call `entityManager.clear()` → forces a fresh DB query on the next `findById()`.

**Practice exercises:**
- Enable `spring.jpa.show-sql=true` and count the SQL queries in different scenarios.
- Demonstrate the "repeatable read" guarantee within a transaction.
- Show how `entityManager.detach(entity)` breaks the first-level cache for a specific entity.
- Demonstrate the N+1 query problem with `@OneToMany` and solve it with `@EntityGraph` / `JOIN FETCH`.

```java
@Transactional
public void demonstrateFirstLevelCache() {
    Order o1 = orderRepo.findById(1L).orElseThrow(); // → SQL SELECT
    Order o2 = orderRepo.findById(1L).orElseThrow(); // → NO SQL (same persistence context)
    assertThat(o1).isSameAs(o2); // exact same object reference

    entityManager.clear(); // evict persistence context

    Order o3 = orderRepo.findById(1L).orElseThrow(); // → SQL SELECT again
    assertThat(o1).isNotSameAs(o3); // different object
}
```

#### 4.3.2 JPA Second-Level Cache (Hibernate L2)

The Hibernate second-level cache lives **across transactions** but within the same JVM. It caches entities and query results.

**Setup:**
- Add dependency: `hibernate-jcache` + `caffeine` (as the JCache/JSR-107 provider).
- Enable in `application.yml`:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        cache:
          use_second_level_cache: true
          use_query_cache: true
          region.factory_class: org.hibernate.cache.jcache.JCacheRegionFactory
      javax:
        cache:
          provider: com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider
```

- Annotate entities with `@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)`:

```java
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Product {
    // Products change rarely → good L2 cache candidate
}
```

**Practice scenarios:**
- `Product` entity (changes rarely) → excellent L2 candidate. Enable cache, load product in TX1, close TX, load in TX2 → NO SQL.
- `Order` entity (changes frequently) → poor L2 candidate. Demonstrate cache invalidation overhead.
- Enable **query cache** for `ProductRepository.findByCategory(category)` — a frequently-read, rarely-changing query.
- Monitor cache hit/miss ratios with Hibernate statistics (`hibernate.generate_statistics=true`).

**When to use vs. skip:**

| Entity | L2 Cache? | Why |
|--------|-----------|-----|
| Product | Yes | Read-heavy, rarely updated |
| Product Category | Yes | Near-static reference data |
| Order | No | Write-heavy, frequent status changes → constant invalidation |
| Customer | Maybe | Read-often, moderate updates → depends on access pattern |

#### 4.3.3 L1 Application Cache — Caffeine (Local, In-Process)

For caching at the **service layer** (not entity-level), Caffeine provides the fastest possible in-JVM cache.

**Setup:**
- Add dependencies: `spring-boot-starter-cache`, `caffeine`.
- Configure Caffeine as the Spring Cache provider:

```java
@Configuration
@EnableCaching
public class CaffeineCacheConfig {

    @Bean
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .recordStats()); // for Micrometer metrics
        return manager;
    }
}
```

- Per-cache custom configuration:

```java
@Bean
public CacheManager caffeineCacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setCacheSpecification("maximumSize=5000,expireAfterWrite=5m");
    return manager;
}

// Or fine-grained per cache name:
@Bean
public CacheManager caffeineCacheManager() {
    SimpleCacheManager manager = new SimpleCacheManager();
    manager.setCaches(List.of(
        buildCache("products", 5000, Duration.ofMinutes(30)),
        buildCache("categories", 500, Duration.ofHours(2)),
        buildCache("orderSummaries", 2000, Duration.ofMinutes(5))
    ));
    return manager;
}

private CaffeineCache buildCache(String name, long maxSize, Duration ttl) {
    return new CaffeineCache(name, Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(ttl)
        .recordStats()
        .build());
}
```

**Practice scenarios:**
- Cache `ProductService.getById()` with `@Cacheable("products")`.
- Cache `CategoryService.getAllCategories()` — static data, long TTL.
- Evict product cache on update with `@CacheEvict("products", key = "#product.id")`.
- Use `@CachePut` when you want to update the cache AND the DB simultaneously.
- Expose Caffeine stats to Micrometer: hit count, miss count, eviction count.

#### 4.3.4 L2 Application Cache — Redis (Distributed)

When running **multiple instances** of the app, Caffeine's local cache becomes stale across nodes. Redis provides a shared, distributed cache.

**Setup:**
- Add dependencies: `spring-boot-starter-data-redis`, `spring-boot-starter-cache`.
- Configure Redis as cache provider:

```java
@Configuration
public class RedisCacheConfig {

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeValuesWith(SerializationPair.fromSerializer(
                new GenericJackson2JsonRedisSerializer()));

        Map<String, RedisCacheConfiguration> perCacheConfig = Map.of(
            "products", defaultConfig.entryTtl(Duration.ofMinutes(30)),
            "orderSummaries", defaultConfig.entryTtl(Duration.ofMinutes(5))
        );

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(perCacheConfig)
            .build();
    }
}
```

**Practice scenarios:**
- Same `@Cacheable` annotations, but now backed by Redis.
- Verify cache survives app restart (unlike Caffeine).
- Verify cache is shared: instance A caches a product, instance B reads it from Redis without hitting DB.
- Custom cache key generation to avoid collisions.
- Docker Compose for local Redis.

#### 4.3.5 Two-Tier Caching — Caffeine (L1) + Redis (L2)

The production-grade pattern: **check Caffeine first (fast), fall back to Redis (shared), then DB.**

**Why:** Caffeine is ~100x faster than Redis (no network hop), but only local. Redis is shared but slower. Combining both gives you the best of both worlds.

**Implementation approach:**
- Create a `CompositeCacheManager` or custom `Cache` implementation that checks Caffeine first, then Redis.
- On cache miss: DB → write to Redis → write to Caffeine.
- On cache evict: evict from Caffeine → evict from Redis (use Redis Pub/Sub to notify other instances to evict their local Caffeine entries).

```java
@Configuration
public class TwoTierCacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager(
            CacheManager caffeineCacheManager,
            CacheManager redisCacheManager) {
        CompositeCacheManager composite = new CompositeCacheManager();
        composite.setCacheManagers(List.of(
            caffeineCacheManager,  // L1: check first
            redisCacheManager      // L2: fallback
        ));
        composite.setFallbackToNoOpCache(false);
        return composite;
    }
}
```

**Advanced: Redis Pub/Sub for cross-instance Caffeine invalidation:**
- When instance A evicts a product from cache, publish a message to Redis channel `cache:evict`.
- All instances subscribe to this channel and evict the key from their local Caffeine.
- This prevents stale local caches across the cluster.

**Practice scenarios:**
- Place a product in cache → verify it exists in both Caffeine and Redis.
- Evict from one instance → verify other instances' Caffeine is also cleared (via pub/sub).
- Simulate Redis downtime → Caffeine still serves cached data (graceful degradation).
- Benchmark: measure response time with no cache → Redis only → Caffeine + Redis.

#### 4.3.6 Testing Caching

| What to Test | How |
|-------------|-----|
| Caffeine cache hit/miss | `@SpringBootTest` + call service twice, `verify(repo, times(1))` |
| Redis cache hit/miss | `@SpringBootTest` + Redis Testcontainer, same verify pattern |
| Two-tier fallback | Mock Redis down, verify Caffeine still serves |
| Cache eviction | Call `@CacheEvict` method, verify next call hits DB |
| Hibernate L2 cache | Enable `hibernate.generate_statistics`, assert `secondLevelCacheHitCount` |
| TTL expiration | Use Caffeine's `Ticker` for time manipulation in tests (avoid `Thread.sleep`) |

### 4.4 Scheduling & Cron Jobs

#### Basic Scheduling

- `@EnableScheduling` with `@Scheduled` methods.
- Three trigger types and when to use each:
  - `fixedRate = 5000` — run every 5 seconds regardless of previous execution time (use for periodic polling).
  - `fixedDelay = 5000` — wait 5 seconds after the previous run finishes (use when tasks must not overlap).
  - `cron = "0 0 2 * * *"` — fine-grained schedule using cron expressions (use for business-hour or time-of-day jobs).

#### Cron Job Scenarios to Implement

| Job | Cron Expression | Purpose |
|-----|----------------|---------|
| Cancel unpaid orders | `0 */5 * * * *` (every 5 min) | Find orders in `PENDING` status older than 30 min, update to `CANCELLED` |
| Daily sales report | `0 0 6 * * MON-FRI` (6 AM weekdays) | Aggregate yesterday's orders, log summary or write to a report table |
| Inventory low-stock alert | `0 0 */2 * * *` (every 2 hours) | Query products with `stock < threshold`, publish alert event |
| Cleanup expired tokens | `0 0 3 * * *` (3 AM daily) | Delete expired refresh tokens from DB |

#### Advanced Scheduling Topics

- **Custom `TaskScheduler` bean** — configure thread pool size for scheduled tasks (default is single-threaded).
- **Conditional scheduling** — use `@ConditionalOnProperty("oms.scheduling.enabled")` to disable cron in test/dev profiles.
- **ShedLock** for distributed locking — when running multiple instances, only ONE should execute the cron job.
  - Configure ShedLock with `@SchedulerLock(name = "cancelExpiredOrders", lockAtLeastFor = "PT1M", lockAtMostFor = "PT5M")`.
  - Back ShedLock with the same PostgreSQL DB (no extra infra).
- **Dynamic cron expressions** — read cron value from `@ConfigurationProperties` or DB instead of hardcoding.
- **Monitoring scheduled jobs** — log execution time, track success/failure counts with Micrometer `Counter` and `Timer`.

#### Testing Scheduled Jobs

- **Unit test the job logic** — extract business logic into a service method, test that method directly.
- **Integration test** — use `Awaitility` to assert the job ran and had the expected side effect.
- **Do NOT rely on actual cron triggers in tests** — call the `@Scheduled` method directly or use `@SpyBean` to verify invocation.

### 4.5 Deliverables Checklist

- [ ] All AOP deliverables from 4.1.7.
- [ ] Async order processing with custom thread pool.
- [ ] JPA first-level cache demo with SQL query counting.
- [ ] Hibernate second-level cache on `Product` entity with Caffeine as JCache provider.
- [ ] Caffeine L1 cache on service methods with per-cache TTL config.
- [ ] Redis L2 cache with custom serialization and TTL per cache name.
- [ ] Two-tier cache (Caffeine + Redis) with `CompositeCacheManager`.
- [ ] Redis Pub/Sub for cross-instance Caffeine invalidation.
- [ ] Cache hit/miss metrics exposed to Micrometer.
- [ ] 4 cron jobs implemented (cancel orders, daily report, low-stock alert, token cleanup).
- [ ] ShedLock on all cron jobs with PostgreSQL backend.
- [ ] Dynamic cron expression from configuration.
- [ ] Unit + integration tests for each cron job.
- [ ] Caching tests: Caffeine, Redis (Testcontainer), two-tier fallback, Hibernate L2 stats.
- [ ] Docker Compose with Redis.

---

## Phase 5 — Event-Driven Architecture & Messaging (Advanced)

**Goal:** Decouple components using Spring Events and introduce message broker integration.

### 5.1 Spring Application Events

- Publish domain events with `ApplicationEventPublisher` (e.g., `OrderCreatedEvent`).
- Listen with `@EventListener` and `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- Understand event ordering and transaction boundaries.

### 5.2 Apache Kafka Integration

- Add `spring-kafka` dependency.
- Configure Kafka producer and consumer (`application.yml`).
- Publish `OrderPlacedEvent` to a Kafka topic.
- Consume events in a separate `@KafkaListener` to simulate an inventory or notification service.
- Error handling: `DefaultErrorHandler`, retry with backoff, dead-letter topic (DLT).
- Docker Compose: add Kafka + Zookeeper (or KRaft mode).

### 5.3 Transactional Outbox Pattern (Stretch)

- Store events in an `outbox` table within the same transaction as the domain change.
- A scheduled poller (or Debezium CDC) publishes outbox rows to Kafka.
- Guarantees at-least-once delivery without 2PC.

### 5.4 Deliverables Checklist

- [ ] Internal Spring events for domain decoupling.
- [ ] Kafka producer/consumer with DLT.
- [ ] Docker Compose with Kafka.
- [ ] (Stretch) Outbox pattern implementation.

---

## Phase 6 — WebSocket & Real-Time Communication (Advanced)

**Goal:** Add real-time, bidirectional communication to the OMS so customers and admins receive live updates without polling.

### 6.1 WebSocket Fundamentals with Spring

- Add `spring-boot-starter-websocket` dependency.
- Understand the two Spring WebSocket models and when to use each:
  - **Raw WebSocket** (`WebSocketHandler`) — low-level, full control, use for custom binary protocols.
  - **STOMP over WebSocket** (`@MessageMapping`) — higher-level, topic-based pub/sub, use for most real-time features.
- Configure `WebSocketMessageBrokerConfigurer`:
  - Register STOMP endpoint: `/ws` with SockJS fallback.
  - Configure simple in-memory broker for destinations: `/topic/*`, `/queue/*`.
  - Set application destination prefix: `/app`.

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins("*").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
```

### 6.2 Real-Time Scenarios to Implement

#### Scenario A: Live Order Status Updates (Topic Broadcast)

**What:** When an order status changes (PENDING → PAID → SHIPPED), push update to all subscribers.
**How:**
- Kafka consumer receives `OrderStatusChangedEvent`.
- Consumer calls `SimpMessagingTemplate.convertAndSend("/topic/orders/{orderId}", statusUpdate)`.
- Frontend subscribes to `/topic/orders/{orderId}` to receive live updates.
**Learn:** Server-side push to a topic, integration between Kafka and WebSocket.

#### Scenario B: User-Specific Notifications (User Queue)

**What:** Send a notification only to the specific customer whose order was updated.
**How:**
- Use `SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/notifications", message)`.
- Client subscribes to `/user/queue/notifications`.
- Spring resolves the user from the session/JWT and routes to the correct WebSocket session.
**Learn:** User-targeted messaging, user destination resolution.

#### Scenario C: Admin Dashboard Live Feed (Topic)

**What:** Admin dashboard shows real-time metrics — new orders per minute, revenue, active users.
**How:**
- A `@Scheduled` method (every 5 seconds) computes live stats and pushes to `/topic/admin/dashboard`.
- Admins subscribe to this topic on page load.
**Learn:** Combining scheduling with WebSocket push.

#### Scenario D: Live Chat Between Customer and Support (Bidirectional)

**What:** Customer sends a chat message → server routes to assigned support agent → agent replies.
**How:**
- `@MessageMapping("/chat.send")` handles incoming messages.
- Route to the correct support agent using `convertAndSendToUser()`.
- Persist chat history in DB.
- Handle user presence: `SessionConnectEvent` / `SessionDisconnectEvent` to track online users.
**Learn:** Full bidirectional messaging, session management, presence tracking.

### 6.3 WebSocket Security

- Integrate Spring Security with WebSocket:
  - Authenticate the STOMP `CONNECT` frame using JWT (extract from headers or query param).
  - Use `ChannelInterceptor` to validate the token before allowing the connection.
  - Authorize topic subscriptions: customers can only subscribe to their own order updates.
- Configure CSRF handling for WebSocket (different from REST).

```java
@Configuration
public class WebSocketSecurityConfig {

    @Bean
    ChannelInterceptor jwtChannelInterceptor(JwtService jwtService) {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                if (CONNECT.equals(accessor.getCommand())) {
                    String token = accessor.getFirstNativeHeader("Authorization");
                    // validate JWT and set Principal
                }
                return message;
            }
        };
    }
}
```

### 6.4 Scalability: External Broker

- **Problem:** The simple in-memory broker doesn't work across multiple app instances (messages only reach clients connected to the same instance).
- **Solution:** Switch to an external STOMP broker (RabbitMQ or ActiveMQ).
  - `registry.enableStompBrokerRelay("/topic", "/queue").setRelayHost("rabbitmq").setRelayPort(61613)`.
- Docker Compose: add RabbitMQ with STOMP plugin enabled.
- Understand when you need this vs. when the simple broker is enough.

### 6.5 Testing WebSocket

#### Unit test `@MessageMapping` handlers

- Test the handler method directly (it's just a method call).
- Verify it calls `SimpMessagingTemplate` with the correct destination and payload.

#### Integration test with `WebSocketStompClient`

- Use `@SpringBootTest(webEnvironment = RANDOM_PORT)`.
- Create a `WebSocketStompClient`, connect to `ws://localhost:{port}/ws`.
- Subscribe to a topic, trigger an action (e.g., place an order via REST), assert the WebSocket message arrives.

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
class OrderStatusWebSocketTest {
    @LocalServerPort int port;

    @Test
    void orderStatusChange_pushedToSubscribers() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(
            new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompSession session = client.connectAsync(
            "ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
            .get(5, SECONDS);

        CompletableFuture<OrderStatusDto> result = new CompletableFuture<>();
        session.subscribe("/topic/orders/1", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return OrderStatusDto.class;
            }
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                result.complete((OrderStatusDto) payload);
            }
        });

        // Trigger order status change via REST API
        restTemplate.put("/api/orders/1/status", new StatusUpdate("SHIPPED"));

        // Assert WebSocket message received
        assertThat(result.get(10, SECONDS).status()).isEqualTo("SHIPPED");
    }
}
```

#### E2E test: chat flow

- Connect two clients (customer + agent).
- Customer sends message → assert agent receives it.
- Agent replies → assert customer receives it.
- Disconnect customer → assert presence event fires.

### 6.6 Deliverables Checklist

- [ ] STOMP over WebSocket configuration with SockJS fallback.
- [ ] Live order status push from Kafka consumer to WebSocket topic.
- [ ] User-specific notifications via `/user/queue/notifications`.
- [ ] Admin dashboard live feed with scheduled push.
- [ ] Chat feature with bidirectional messaging and presence tracking.
- [ ] WebSocket security: JWT authentication on CONNECT, subscription authorization.
- [ ] External broker (RabbitMQ) setup for multi-instance scalability.
- [ ] Integration test with `WebSocketStompClient`.
- [ ] E2E test for chat flow.
- [ ] Docker Compose: add RabbitMQ with STOMP plugin.

---

## Phase 7 — Observability & Production Readiness (Advanced)

**Goal:** Make the application production-observable with metrics, tracing, logging, and health checks.

### 7.1 Spring Boot Actuator

- Expose health, info, metrics, and env endpoints.
- Custom `HealthIndicator` (e.g., check Kafka connectivity).
- Custom `@Endpoint` for admin operations.

### 7.2 Structured Logging

- Configure Logback with JSON output (for log aggregation).
- Add MDC context (correlation ID) using a servlet filter.
- Propagate correlation ID in Kafka message headers.

### 7.3 Metrics with Micrometer + Prometheus

- Add `micrometer-registry-prometheus`.
- Expose `/actuator/prometheus` endpoint.
- Custom metrics: `Counter` for orders placed, `Timer` for order processing duration, `Gauge` for active orders.
- Docker Compose: add Prometheus + Grafana with a pre-built dashboard.

### 7.4 Distributed Tracing with Micrometer Tracing

- Add `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-zipkin`.
- Traces automatically propagate across REST calls and Kafka messages.
- Docker Compose: add Zipkin.

### 7.5 Deliverables Checklist

- [ ] Actuator with custom health indicator.
- [ ] Structured JSON logs with correlation ID.
- [ ] Prometheus metrics + Grafana dashboard.
- [ ] Distributed tracing with Zipkin.
- [ ] Full Docker Compose: PostgreSQL, Redis, Kafka, Prometheus, Grafana, Zipkin.

---

## Phase 8 — Microservices & Deployment (Expert)

**Goal:** Split the monolith into microservices and set up cloud-native deployment.

### 8.1 Multi-Module / Multi-Service Split

- Extract into separate Spring Boot apps: `order-service`, `product-service`, `notification-service`.
- Each service has its own database (database-per-service pattern).
- Communicate via REST (OpenFeign or RestClient) and Kafka events.

### 8.2 API Gateway & Service Discovery

- **Spring Cloud Gateway** as the edge gateway (routing, rate limiting, circuit breaker).
- **Spring Cloud Netflix Eureka** or **Consul** for service discovery (or use Docker DNS if preferred).

### 8.3 Resilience Patterns

- **Resilience4j** integration: Circuit Breaker, Retry, Rate Limiter, Bulkhead.
- Annotate Feign clients/REST calls with `@CircuitBreaker`, `@Retry`.
- Fallback methods for graceful degradation.

### 8.4 Centralized Configuration

- **Spring Cloud Config Server** backed by Git or a local file system.
- `@RefreshScope` for dynamic config reload.

### 8.5 Containerization & Orchestration

- Multi-stage `Dockerfile` for each service (using `eclipse-temurin` base image).
- Or use Spring Boot's built-in `spring-boot:build-image` (Buildpacks).
- `docker-compose.yml` to run all services + infrastructure locally.
- (Stretch) Kubernetes manifests: `Deployment`, `Service`, `ConfigMap`, `Ingress`.

### 8.6 Deliverables Checklist

- [ ] 3 microservices communicating via REST + Kafka.
- [ ] API Gateway with rate limiting.
- [ ] Resilience4j circuit breakers.
- [ ] Full Docker Compose for local development.
- [ ] (Stretch) Kubernetes deployment manifests.

---

## Phase 9 — Comprehensive Testing Strategy (Cross-Cutting)

> **This phase runs in parallel with Phases 1–8.** Each section below teaches you *when* and *why* to use a specific test type, with concrete OMS scenarios so you build the muscle memory to pick the right approach in real projects.

### 9.1 Testing Pyramid — Mental Model

```
        ╱  E2E Tests  ╲          ← Few, slow, high confidence
       ╱───────────────╲
      ╱ Integration Tests╲       ← Moderate count, real infra
     ╱─────────────────────╲
    ╱     Unit Tests         ╲   ← Many, fast, isolated
   ╱───────────────────────────╲
```

**Rule of thumb for real projects:**
- **Unit test** → pure logic, no Spring context, no I/O. Fast feedback.
- **Integration test** → verify that Spring wiring, DB queries, or external systems work together.
- **E2E test** → validate a full user journey through the running application (HTTP → service → DB → response).

---

### 9.2 Unit Tests — When & How

**When to use:** Testing business logic that does NOT depend on Spring context, database, or external services. If you can test it with `new MyService(mockDep)`, it's a unit test.

#### Scenario A: Pure Service Logic

**What:** `OrderService.calculateTotal(order)` — computes order total with discounts.
**Why unit test:** Pure calculation, no DB or HTTP needed.
**Practice:**
- Use JUnit 5 + Mockito.
- Mock `OrderRepository`, `ProductRepository` — only test the calculation logic.
- Test edge cases: empty order, null items, negative quantity, max discount boundary.

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock OrderRepository orderRepo;
    @Mock ProductRepository productRepo;
    @InjectMocks OrderService orderService;

    @Test
    void calculateTotal_appliesDiscountWhenAboveThreshold() { ... }

    @Test
    void calculateTotal_throwsWhenOrderHasNoItems() { ... }
}
```

#### Scenario B: MapStruct Mapper

**What:** `OrderMapper.toDto(order)` — entity to DTO mapping.
**Why unit test:** MapStruct generates a plain Java class, no Spring needed.
**Practice:**
- Instantiate the mapper impl directly.
- Verify all fields map correctly, especially nested `OrderItem` → `OrderItemDto`.
- Test null handling.

#### Scenario C: Custom Validator

**What:** `@ValidOrderStatus` constraint validator.
**Why unit test:** Validator logic is pure — given an input, return valid/invalid.
**Practice:**
- Instantiate validator, call `isValid()` directly.
- Test every valid enum value and invalid strings.

#### Scenario D: Domain Event Construction

**What:** `OrderCreatedEvent.from(order)` builds the event payload.
**Why unit test:** No I/O, just object construction.

#### When NOT to unit test
- Repository methods (they need a DB — use integration test).
- Controller request mapping / serialization (use slice test).
- Anything where the interesting behavior IS the Spring wiring.

#### Deliverables
- [ ] `OrderServiceTest` — 8+ test cases covering happy path, edge cases, exceptions.
- [ ] `OrderMapperTest` — field mapping, null input, nested objects.
- [ ] `OrderStatusValidatorTest` — valid/invalid values.
- [ ] Practice parameterized tests with `@ParameterizedTest` + `@CsvSource` for discount tiers.

---

### 9.3 Integration Tests — When & How

**When to use:** Testing that multiple components work together correctly — Spring context, real (or containerized) database, actual Spring Security filter chain, real cache, real Kafka broker.

#### Scenario E: Repository Layer (`@DataJpaTest`)

**What:** Test custom `@Query` methods, Specifications, pagination.
**Why integration test:** You need Hibernate + a real DB to verify SQL correctness.
**Practice:**
- Use `@DataJpaTest` + Testcontainers PostgreSQL (NOT H2 — match production).
- Seed data with `@Sql` scripts or `TestEntityManager`.
- Test: `findOrdersByCustomerIdAndStatus(customerId, status, pageable)`.
- Test: Specification that filters by date range + min total.
- Verify auditing fields (`createdAt`, `updatedAt`) are auto-populated.

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = NONE)
class OrderRepositoryIntegrationTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired OrderRepository orderRepo;
    @Autowired TestEntityManager em;

    @Test
    void findByStatus_returnsPaginatedResults() { ... }
}
```

#### Scenario F: Controller Slice Test (`@WebMvcTest`)

**What:** Test controller request mapping, validation, serialization, error responses — WITHOUT starting the full app.
**Why integration test (slice):** You need Spring MVC wired up, but NOT the DB or services (mock them).
**Practice:**
- Use `@WebMvcTest(OrderController.class)` + `@MockBean` for services.
- Test: POST `/orders` with invalid body → 400 with validation errors.
- Test: GET `/orders/{id}` for non-existent order → 404 with `ProblemDetail`.
- Test: Correct JSON serialization of response DTOs.
- Test: Content negotiation (Accept header).

```java
@WebMvcTest(OrderController.class)
class OrderControllerSliceTest {
    @Autowired MockMvc mockMvc;
    @MockBean OrderService orderService;

    @Test
    void createOrder_invalidBody_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").exists());
    }
}
```

#### Scenario G: Security Integration Test (`@WebMvcTest` + Security)

**What:** Test that security rules (authentication, role-based access) are enforced correctly.
**Why integration test:** Security is a cross-cutting filter chain — you need Spring Security context loaded.
**Practice:**
- Test: Unauthenticated request → 401.
- Test: `ROLE_CUSTOMER` accessing admin-only endpoint → 403.
- Test: `ROLE_ADMIN` accessing admin endpoint → 200.
- Use `@WithMockUser(roles = "ADMIN")` and custom `@WithMockJwt` annotation.

#### Scenario H: Caching Integration Test

**What:** Verify `@Cacheable` actually caches — second call does NOT hit the repository.
**Why integration test:** Caching is Spring AOP proxy magic; only works with a real Spring context.
**Practice:**
- Use `@SpringBootTest` with Redis Testcontainer.
- Call `productService.getById(1)` twice.
- Verify `productRepository.findById(1)` was called exactly ONCE (via Mockito `verify`).
- Test `@CacheEvict` on product update.

#### Scenario I: Kafka Integration Test

**What:** Verify messages are produced and consumed correctly with serialization/deserialization.
**Why integration test:** Need a real Kafka broker for partition assignment, consumer groups, DLT routing.
**Practice:**
- Use `@EmbeddedKafka` or Testcontainers Kafka.
- Produce an `OrderPlacedEvent`, assert the consumer receives and processes it.
- Test DLT: send a poison-pill message, verify it lands in the dead-letter topic.
- Test idempotency: send the same event twice, verify order is not processed twice.

```java
@SpringBootTest
@EmbeddedKafka(topics = {"orders", "orders.DLT"})
class OrderKafkaIntegrationTest {
    @Autowired KafkaTemplate<String, OrderPlacedEvent> producer;
    @Autowired OrderRepository orderRepo;

    @Test
    void orderPlacedEvent_isConsumedAndPersisted() { ... }

    @Test
    void malformedEvent_routedToDeadLetterTopic() { ... }
}
```

#### Scenario J: Async Processing Integration Test

**What:** Verify `@Async` methods execute on the custom thread pool and complete correctly.
**Why integration test:** `@Async` requires Spring's proxy; plain unit tests bypass it.
**Practice:**
- Call async method, get `CompletableFuture`, assert `.get()` returns expected result.
- Verify execution happens on the correct thread pool by checking `Thread.currentThread().getName()`.
- Test exception handling in async flows.

#### When NOT to integration test
- Pure calculations / mapping logic (use unit test — faster).
- Full user journeys spanning multiple API calls (use E2E test).

#### Deliverables
- [ ] `OrderRepositoryIntegrationTest` — Testcontainers, custom queries, specifications, auditing.
- [ ] `OrderControllerSliceTest` — validation, error response format, serialization.
- [ ] `SecurityIntegrationTest` — 401/403/200 per role.
- [ ] `CachingIntegrationTest` — verify cache hit/miss with Redis Testcontainer.
- [ ] `KafkaIntegrationTest` — produce/consume, DLT, idempotency.
- [ ] `AsyncIntegrationTest` — thread pool, CompletableFuture, exception handling.

---

### 9.4 End-to-End (E2E) Tests — When & How

**When to use:** Validating a complete user journey that crosses multiple layers — HTTP request in, through security, service, DB, events, and back. The app runs as a real server (not mocked).

#### Scenario K: Full Order Lifecycle

**What:** Customer registers → logs in → creates order → pays → order status updates → receives confirmation.
**Why E2E:** This journey spans auth, business logic, DB persistence, and event publishing. No single layer test covers it.
**Practice:**
- Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` or `WebTestClient`.
- Testcontainers for PostgreSQL, Redis, Kafka (all real infra).
- Steps:
  1. `POST /auth/register` → assert 201.
  2. `POST /auth/login` → extract JWT token.
  3. `POST /api/orders` with JWT → assert 201, order in `PENDING` status.
  4. `POST /api/orders/{id}/pay` → assert 200, order in `PAID` status.
  5. Verify Kafka message was published (use a test consumer).
  6. Verify notification consumer processed the event.
  7. `GET /api/orders/{id}` → assert order reflects final state.

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class OrderLifecycleE2ETest {
    @Container static PostgreSQLContainer<?> pg = ...;
    @Container static KafkaContainer kafka = ...;
    @Container static GenericContainer<?> redis = ...;

    @Autowired TestRestTemplate rest;

    @Test
    void fullOrderLifecycle_fromRegistrationToConfirmation() {
        // 1. Register
        var regResponse = rest.postForEntity("/auth/register", registerBody, Void.class);
        assertThat(regResponse.getStatusCode()).isEqualTo(CREATED);

        // 2. Login
        var loginResponse = rest.postForEntity("/auth/login", loginBody, TokenDto.class);
        String jwt = loginResponse.getBody().token();

        // 3. Create order (with JWT)
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        var orderResponse = rest.exchange("/api/orders", POST,
            new HttpEntity<>(orderBody, headers), OrderDto.class);
        assertThat(orderResponse.getBody().status()).isEqualTo("PENDING");

        // ... continue through payment, verification, etc.
    }
}
```

#### Scenario L: Admin Bulk Operations

**What:** Admin logs in → fetches paginated orders → updates order status in bulk → verifies cache is evicted.
**Why E2E:** Tests admin authorization + pagination + cache invalidation across a real request flow.
**Practice:**
- Seed DB with 50 orders.
- Login as admin, fetch page 1 (size=10), verify pagination metadata.
- Bulk update status, verify cache eviction by checking next GET hits DB.

#### Scenario M: Error Recovery Journey

**What:** Customer places order → payment service is down → circuit breaker opens → customer sees graceful error → payment recovers → retry succeeds.
**Why E2E:** Tests resilience across the full stack — can only be validated with a running app + simulated failures.
**Practice:**
- Use **WireMock** to simulate the external payment service.
- Configure WireMock to return 500 for first 3 calls, then 200.
- Verify the circuit breaker opened (check Actuator metrics).
- Verify the customer received a meaningful error (not a 500 stack trace).
- Verify retry eventually succeeds.

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@WireMockTest(httpPort = 9090)
class PaymentResilienceE2ETest {
    @Test
    void circuitBreaker_opensOnRepeatedFailure_thenRecovers() {
        // Stub payment service: fail 3 times, then succeed
        stubFor(post("/payments").inScenario("recovery")
            .whenScenarioStateIs(STARTED)
            .willReturn(serverError())
            .willSetStateTo("failing"));
        // ... configure state transitions ...

        // Place order → expect graceful degradation
        // Wait → place order again → expect success
    }
}
```

#### Scenario N: Concurrent Order Placement (Stress/Race Condition)

**What:** 10 customers simultaneously order the last 5 items in stock.
**Why E2E:** Race conditions only surface under real concurrency with a real DB.
**Practice:**
- Use `ExecutorService` to fire 10 concurrent POST requests.
- Assert exactly 5 orders succeed and 5 get "out of stock" errors.
- Assert no negative inventory (DB constraint holds).
- This teaches optimistic locking (`@Version`) or pessimistic locking in practice.

#### Scenario O: Cross-Service E2E (Phase 6)

**What:** Order placed in `order-service` → event consumed by `notification-service` → email sent.
**Why E2E:** Validates the full microservice communication chain.
**Practice:**
- All services running via Docker Compose (or Testcontainers Compose).
- Place order via API Gateway.
- Poll or await assertion that notification-service persisted the notification record.
- Use **Awaitility** for async assertions:

```java
await().atMost(10, SECONDS).untilAsserted(() ->
    assertThat(notificationRepo.findByOrderId(orderId)).isPresent()
);
```

#### When NOT to E2E test
- Testing a single method's logic (use unit test).
- Testing DB query correctness (use `@DataJpaTest`).
- Anything that can be validated faster at a lower level.

#### Deliverables
- [ ] `OrderLifecycleE2ETest` — full journey from registration to order confirmation.
- [ ] `AdminBulkOperationsE2ETest` — pagination, bulk update, cache eviction.
- [ ] `PaymentResilienceE2ETest` — WireMock, circuit breaker, graceful degradation.
- [ ] `ConcurrentOrderE2ETest` — race conditions, optimistic locking validation.
- [ ] `CrossServiceE2ETest` — multi-service with Docker Compose / Testcontainers.

---

### 9.5 Test Infrastructure & Utilities

These cross-cutting tools make your tests cleaner and more maintainable.

#### Testcontainers Shared Instances

- Create a `@TestConfiguration` class with `@ServiceConnection` for reusable containers across all test classes.
- Avoid spinning up a new PostgreSQL/Redis/Kafka per test class (slow).

```java
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16").withReuse(true);
    }

    @Bean
    @ServiceConnection
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7").withExposedPorts(6379).withReuse(true);
    }
}
```

#### Custom Test Annotations

- `@IntegrationTest` — meta-annotation combining `@SpringBootTest` + `@Testcontainers` + `@ActiveProfiles("test")`.
- `@WithMockAdmin` — custom security annotation for admin tests.

#### Test Data Builders

- Builder pattern or **Instancio** library for generating test entities.
- Avoid hardcoded test data scattered across test classes.

#### Awaitility for Async Assertions

- Wrap all async/eventual-consistency checks with Awaitility.
- Avoid `Thread.sleep()` in tests.

#### WireMock for External Services

- Simulate third-party APIs (payment gateway, email service).
- Test timeouts, error responses, rate limits.

#### Deliverables
- [ ] `TestcontainersConfig` with shared, reusable containers.
- [ ] `@IntegrationTest` and `@WithMockAdmin` custom annotations.
- [ ] Test data builder for `Order`, `Customer`, `Product`.
- [ ] WireMock stubs for payment service.

---

### 9.6 Decision Guide — Which Test to Write?

Use this table when you're in a real project and need to decide:

| Situation | Test Type | Why |
|-----------|-----------|-----|
| Pure calculation / transformation | Unit | No I/O, fast, many edge cases |
| DTO mapping (MapStruct) | Unit | Generated code, no Spring needed |
| Custom validator | Unit | Pure boolean logic |
| Custom `@Query` / Specification | Integration (`@DataJpaTest`) | Need real SQL execution |
| Controller validation & error format | Integration (`@WebMvcTest`) | Need MVC dispatch + serialization |
| Security rules (401/403) | Integration (`@WebMvcTest` + Security) | Need filter chain |
| Caffeine L1 cache hit/miss | Integration (`@SpringBootTest`) | Need AOP proxy + real Caffeine |
| Redis L2 cache hit/miss | Integration (`@SpringBootTest`) | Need AOP proxy + Redis Testcontainer |
| Two-tier cache fallback | Integration (`@SpringBootTest`) | Need both cache managers wired |
| Hibernate L2 cache | Integration (`@DataJpaTest`) | Need Hibernate stats + real DB |
| Kafka produce/consume | Integration (`@SpringBootTest`) | Need real broker |
| Custom AOP aspect (audit, retry) | Integration (`@SpringBootTest`) | Need proxy + Spring context |
| AOP self-invocation bug | Integration (`@SpringBootTest`) | Need proxy to demonstrate bypass |
| Async method execution | Integration (`@SpringBootTest`) | Need AOP proxy |
| Multi-datasource routing | Integration (`@SpringBootTest`) | Need real DB containers + routing verification |
| Cross-DB compensating transaction | Integration (`@SpringBootTest`) | Need both DBs to verify rollback/compensation |
| Full user journey (multi-step) | E2E | Validates the real user experience |
| Race conditions / concurrency | E2E | Need real DB locks & isolation |
| Resilience (circuit breaker, retry) | E2E + WireMock | Need real HTTP + failure simulation |
| Cross-service communication | E2E (Docker Compose) | Need multiple running services |

### 9.7 Testing Anti-Patterns to Avoid

Practice recognizing these so you don't repeat them in real projects:

1. **Using H2 instead of Testcontainers** — H2 behaves differently from PostgreSQL (JSON columns, window functions, locking). Always match your production DB.
2. **`@SpringBootTest` for everything** — Loads the full context even when `@WebMvcTest` or `@DataJpaTest` would suffice. Slows down the suite.
3. **`Thread.sleep()` in async tests** — Flaky. Use Awaitility instead.
4. **Mocking the class under test** — If you're mocking the thing you're testing, your test proves nothing.
5. **No test isolation** — Tests sharing mutable state (shared DB rows without cleanup). Use `@Transactional` on test classes or `@Sql` scripts.
6. **Testing framework code** — Don't test that `@GetMapping` works. Test YOUR logic and configuration.
7. **Giant E2E tests with no lower-level coverage** — The inverted testing pyramid. Slow, brittle, hard to debug.

### 9.8 Deliverables Checklist (Full Phase 9)

- [ ] **Unit tests:** 20+ tests across services, mappers, validators.
- [ ] **Integration tests:** Repository, Controller, Security, Cache, Kafka, Async (6 test classes).
- [ ] **E2E tests:** Order lifecycle, admin ops, resilience, concurrency, cross-service (5 test classes).
- [ ] **Test infra:** Testcontainers shared config, custom annotations, data builders, WireMock stubs.
- [ ] **No `Thread.sleep()`**, no H2, no `@SpringBootTest` where a slice test suffices.
- [ ] **Coverage target:** 80%+ line coverage on service and controller layers.

---

## Recommended Learning Path

| Week    | Phase   | Focus                                                    |
|---------|---------|----------------------------------------------------------|
| 1–2     | Phase 1 | Foundations, configuration + basic unit/slice tests       |
| 3       | Phase 2 | Multiple data sources, cross-DB transactions, routing     |
| 4       | Phase 3 | Security & JWT + security integration tests               |
| 5       | Phase 4 | Async, caching, cron jobs + cache/async tests             |
| 6–7     | Phase 5 | Event-driven, Kafka + Kafka integration tests             |
| 8       | Phase 6 | WebSocket & real-time communication                       |
| 9       | Phase 7 | Observability stack                                       |
| 10–12   | Phase 8 | Microservices & deployment                                |
| Ongoing | Phase 9 | Testing at every phase — add tests as you build           |

> **Phase 9 is not sequential** — you practice the relevant testing scenarios as you complete each phase. By the end, you'll have a complete test suite spanning unit, integration, and E2E.

## Tech Stack Summary

| Component     | Technology                                            |
|---------------|-------------------------------------------------------|
| Framework     | Spring Boot 3.x, Java 17+                            |
| Database      | PostgreSQL, MySQL (multi-datasource), Testcontainers  |
| Cache         | Caffeine (L1), Redis (L2), Hibernate L2 (JCache)     |
| Messaging     | Apache Kafka                                          |
| Real-Time     | WebSocket (STOMP), SockJS, RabbitMQ (STOMP relay)     |
| Security      | Spring Security 6, JWT                                |
| Scheduling    | Spring Scheduler, ShedLock                            |
| Resilience    | Resilience4j                                          |
| Observability | Actuator, Micrometer, Prometheus, Grafana, Zipkin     |
| Gateway       | Spring Cloud Gateway                                  |
| Containers    | Docker, Docker Compose                                |
| Build         | Maven (or Gradle)                                     |
| Testing       | JUnit 5, Mockito, Testcontainers, WireMock, Awaitility |
