# Order Management System (OMS) — Project Context

## Project Overview

This is an **Advanced Spring Boot Practice Project** implementing a production-grade Order Management System. The project demonstrates progressive mastery of Spring Boot 3.x concepts across multiple phases.

**Current Status:** Phase 3 (Security & Authentication) completed.

**Core Technologies:**
- **Framework:** Spring Boot 3.4.3
- **Language:** Java 21
- **Build Tool:** Maven
- **Databases:** PostgreSQL (JPA), MongoDB (document store)
- **Security:** Spring Security 6, JWT authentication
- **Key Libraries:** Lombok, MapStruct, Flyway, Testcontainers, JJWT

---

## Project Structure

```
advanced-spring-boot/
├── src/
│   ├── main/
│   │   ├── java/com/oms/
│   │   │   ├── config/           # Configuration classes
│   │   │   │   ├── JpaAuditingConfig.java
│   │   │   │   ├── MongoConfig.java
│   │   │   │   ├── OrderProperties.java
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   ├── JwtService.java
│   │   │   │   ├── JwtAuthenticationFilter.java
│   │   │   │   ├── CustomUserDetailsService.java
│   │   │   │   └── routing/      # Read/write routing
│   │   │   ├── controller/       # REST controllers
│   │   │   ├── dto/              # Request/Response DTOs
│   │   │   ├── entity/           # JPA entities (User, Order, Product, Customer)
│   │   │   ├── exception/        # Custom exceptions + GlobalExceptionHandler
│   │   │   ├── mapper/           # MapStruct mappers
│   │   │   ├── mongo/            # MongoDB domain (documents, repository)
│   │   │   ├── repository/       # Spring Data repositories
│   │   │   ├── service/          # Business logic layer
│   │   │   ├── validation/       # Custom validators
│   │   │   └── OmsApplication.java
│   │   └── resources/
│   │       ├── db/migration/     # Flyway SQL migrations (V1-V4)
│   │       └── application*.yml  # Multi-profile configuration
│   └── test/java/com/oms/        # Test classes
├── docs/                         # Phase documentation
│   ├── phase-1.md
│   ├── phase-2.md
│   └── phase-3.md
├── docker-compose.yml            # PostgreSQL (primary + replica) + MongoDB
└── pom.xml                       # Maven configuration
```

---

## Building and Running

### Prerequisites
- Java 21+ installed
- Maven 3.6+
- Docker Desktop (for databases)

### Quick Start

```bash
# 1. Start infrastructure (PostgreSQL + MongoDB)
docker-compose up -d

# 2. Build and run with dev profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Application available at http://localhost:8080
```

### Build Commands

```bash
# Clean compile
mvn clean compile

# Run all tests
mvn test

# Package as JAR
mvn clean package

# Skip tests during build
mvn clean package -DskipTests
```

### Test Commands

```bash
# Run specific test class
mvn test -Dtest=AuthServiceTest

# Run tests with coverage (if jacoco configured)
mvn test jacoco:report
```

### Profiles

| Profile | Database | Configuration |
|---------|----------|---------------|
| `dev` | PostgreSQL (localhost:5432) + MongoDB | H2 console disabled, SQL logging enabled |
| `prod` | PostgreSQL (env-based) + MongoDB | Production settings |

---

## Architecture

### Multi-Datasource Design

```
┌─────────────────────────────────────────────────────────────┐
│                      REST Controllers                        │
│  OrderController │ CustomerController │ AuthController      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Spring Security Filter Chain                    │
│  JwtAuthenticationFilter → AuthenticationManager            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                       Service Layer                          │
│         OrderService │ OrderEventService │ AuthService      │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
    ┌──────────────────┐            ┌──────────────────┐
    │   JPA/PostgreSQL │            │     MongoDB      │
    │   (OLTP - Orders)│            │  (Events/Audit)  │
    └──────────────────┘            └──────────────────┘
```

### Key Architectural Patterns

| Pattern | Implementation |
|---------|----------------|
| **Multi-datasource** | JPA (PostgreSQL) for orders, MongoDB for event audit log |
| **Cross-database coordination** | Best-effort pattern with compensating transaction |
| **Read/write routing** | `AbstractRoutingDataSource` + `@ReadOnly` annotation + AOP |
| **Schema migrations** | Flyway (V1-V4 SQL scripts) |
| **DTO mapping** | MapStruct with Spring component model |
| **Validation** | Bean Validation + custom `@ValidOrderStatus` |
| **Error handling** | `@RestControllerAdvice` with RFC 9457 `ProblemDetail` |
| **Authentication** | JWT-based stateless authentication |
| **Authorization** | Role-based access control (ROLE_CUSTOMER, ROLE_ADMIN) |
| **Auditing** | JPA (`@CreatedDate`, `@LastModifiedDate`) + MongoDB events |

---

## API Endpoints

### Authentication (Phase 3)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/register` | Register new user |
| POST | `/api/auth/login` | Login with credentials |
| POST | `/api/auth/refresh` | Refresh access token |

### Customers
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/customers` | List all (paginated) |
| GET | `/api/customers/{id}` | Get by ID |
| POST | `/api/customers` | Create (authenticated) |
| PUT | `/api/customers/{id}` | Update (authenticated) |
| DELETE | `/api/customers/{id}` | Delete (authenticated) |

### Products
| Method | Endpoint | Description | Access |
|--------|----------|-------------|--------|
| GET | `/api/products` | List all (paginated) | Public |
| GET | `/api/products/{id}` | Get by ID | Public |
| POST | `/api/products` | Create | ADMIN only |
| PUT | `/api/products/{id}` | Update | ADMIN only |
| DELETE | `/api/products/{id}` | Delete | ADMIN only |

### Orders
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/orders` | List all (paginated) |
| GET | `/api/orders/{id}` | Get by ID |
| POST | `/api/orders` | Create order (authenticated) |
| PATCH | `/api/orders/{id}/status` | Update status (authenticated) |
| DELETE | `/api/orders/{id}` | Cancel order (authenticated) |
| GET | `/api/orders/search` | Search with filters |
| GET | `/api/orders/above-amount` | Filter by amount |
| GET | `/api/orders/{id}/events` | Get order events |

### Events
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/events/recent` | Get recent events since timestamp |

---

## Testing Strategy

### Test Types

| Type | Annotation | Purpose |
|------|------------|---------|
| **Unit** | `@ExtendWith(MockitoExtension.class)` | Isolated service/validation tests |
| **Slice (JPA)** | `@DataJpaTest` | Repository + JPA tests with Testcontainers |
| **Slice (Mongo)** | `@DataMongoTest` | MongoDB repository tests |
| **Slice (Web)** | `@WebMvcTest` | Controller layer tests |
| **Integration** | `@SpringBootTest + @Testcontainers` | Full context with PostgreSQL + MongoDB |

### Testcontainers Usage

Tests spin up real PostgreSQL and MongoDB containers for integration testing:

```java
@Testcontainers
@SpringBootTest
class MultiDataSourceIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = ...;
    @Container static MongoDBContainer mongo = ...;
    
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }
}
```

---

## Development Conventions

### Code Style
- **Lombok** for boilerplate reduction (`@Data`, `@Builder`, `@RequiredArgsConstructor`)
- **Records** for DTOs (immutable request/response objects)
- **MapStruct** for entity ↔ DTO mapping (compile-time generated)

### Entity Design
- All JPA entities extend `BaseEntity` (auditing via `@EntityListeners`)
- MongoDB documents use `@Document` with `@Indexed` fields
- Embedded documents for denormalized data (`OrderSnapshot`, `OrderItemSnapshot`)

### Transaction Management
- `@Transactional` on service methods (JPA only)
- MongoDB writes are best-effort (outside JPA transaction)
- Compensating transaction pattern for cross-database consistency

### Configuration
- `@ConfigurationProperties(prefix = "oms.order")` for typed config binding
- Multi-profile YAML: `application.yml`, `application-dev.yml`, `application-prod.yml`

---

## Docker Compose Services

```yaml
services:
  postgres:        # Primary DB (port 5432)
  postgres-replica # Read replica (port 5433)
  mongodb:         # Event store (port 27017)
```

---

## Key Implementation Details

### Phase 1 — Foundations (Completed)
- CRUD REST APIs for Customer, Product, Order
- JPA entities with auditing
- Dynamic queries via Specifications
- Custom validation annotations
- Global exception handling with ProblemDetail
- 26 passing tests

### Phase 2 — Multi-Datasource (Completed)
- MongoDB integration for order event audit log
- Cross-database event publishing (best-effort pattern)
- Read/write routing with `AbstractRoutingDataSource`
- Flyway schema migrations (V1-V3)
- Testcontainers for PostgreSQL + MongoDB tests

### Phase 3 — Security & Authentication (Completed)
- Spring Security 6 with JWT authentication
- User registration and login endpoints
- Role-based access control (CUSTOMER, ADMIN)
- `@PreAuthorize` method-level security
- Custom `@CurrentUser` annotation
- BCrypt password hashing
- JWT access + refresh token flow

### Future Phases (Planned)
- **Phase 4:** AOP, Async Processing, Caching & Scheduling
- **Phase 5+:** Advanced patterns (CQRS, Event Sourcing, etc.)

---

## Troubleshooting

### Docker not running
```bash
# Error: Testcontainers fails to start
# Solution: Ensure Docker Desktop is running before `mvn test`
```

### Database connection refused
```bash
# Error: Connection refused to localhost:5432 or :27017
# Solution: Run `docker-compose up -d` before starting the app
```

### Flyway migration errors
```bash
# Error: Flyway validation failed
# Solution: Ensure `ddl-auto: validate` and databases are clean
# Run `docker-compose down -v` to reset volumes
```

### MapStruct not generating mappers
```bash
# Error: Cannot find mapper implementation
# Solution: Run `mvn clean compile` to trigger annotation processing
```

### JWT authentication issues
```bash
# Error: 401 Unauthorized on protected endpoints
# Solution: Include valid JWT token in Authorization header
# Format: Authorization: Bearer <token>
```
