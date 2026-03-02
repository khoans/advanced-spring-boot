# Phase 3 — Security & Authentication

## What Was Implemented

Phase 3 adds **Spring Security 6** with **JWT-based authentication** to the Order Management System. The implementation includes user registration, login with JWT token generation, role-based authorization, and comprehensive security testing.

### Architecture Overview

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
│         AuthService │ UserService │ OrderService            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   PostgreSQL (Users)                         │
│  users table with BCrypt password hashes                     │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Purpose |
|-----------|---------|
| `User` entity | JPA entity implementing Spring Security's `UserDetails` |
| `UserRepository` | Spring Data JPA repository for user data access |
| `JwtService` | JWT token generation, validation, and claim extraction |
| `AuthService` | User registration, login, and token refresh logic |
| `AuthController` | REST endpoints for `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh` |
| `JwtAuthenticationFilter` | Extracts and validates JWT from `Authorization` header |
| `SecurityConfig` | Security filter chain configuration with role-based access control |
| `@CurrentUser` | Custom annotation for injecting authenticated user |

### Security Configuration

**Authentication Flow:**
1. User registers via `/api/auth/register` → receives access token + refresh token
2. User logs in via `/api/auth/login` → receives access token + refresh token
3. Subsequent requests include `Authorization: Bearer <token>` header
4. `JwtAuthenticationFilter` extracts token, validates it, and sets authentication context
5. Spring Security grants/denies access based on roles

**Access Control:**
| Endpoint Pattern | Method | Access |
|-----------------|--------|--------|
| `/api/auth/**` | ALL | Public |
| `GET /api/orders/**` | GET | Public |
| `GET /api/products/**` | GET | Public |
| `GET /api/customers/**` | GET | Public |
| `POST /api/orders/**` | POST | Authenticated |
| `PUT /api/orders/**` | PUT | Authenticated |
| `DELETE /api/orders/**` | DELETE | Authenticated |
| `POST /api/products/**` | POST | ADMIN only |
| `PUT /api/products/**` | PUT | ADMIN only |
| `DELETE /api/products/**` | DELETE | ADMIN only |

### JWT Token Configuration

```yaml
oms:
  security:
    jwt:
      secret-key: ${JWT_SECRET_KEY:default-dev-key-change-in-prod}
      expiration-ms: 86400000        # 24 hours
      refresh-expiration-ms: 604800000  # 7 days
```

## Database Schema

### Users Table (Flyway V4)

```sql
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(255) NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password        VARCHAR(255) NOT NULL,
    role            VARCHAR(50) NOT NULL DEFAULT 'ROLE_CUSTOMER',
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
```

## New Files Created

| Category | Files |
|----------|-------|
| Entity (1) | `User.java` |
| Repository (1) | `UserRepository.java` |
| Config (4) | `JwtService.java`, `JwtAuthenticationFilter.java`, `SecurityConfig.java`, `CustomUserDetailsService.java`, `CurrentUser.java` |
| Service (1) | `AuthService.java` |
| Controller (1) | `AuthController.java` |
| DTOs (3) | `AuthRequest.java`, `RegisterRequest.java`, `AuthResponse.java` |
| Exception Handler (1) | `GlobalExceptionHandler.java` (modified) |
| Flyway (1) | `V4__create_user_table.sql` |
| Tests (3) | `JwtServiceTest.java`, `AuthControllerIntegrationTest.java`, `SecurityIntegrationTest.java` |

## Modified Files

| File | Change |
|------|--------|
| `pom.xml` | Added Spring Security, JJWT dependencies |
| `application.yml` | Added JWT configuration properties |
| `GlobalExceptionHandler.java` | Added `BadCredentialsException` handler |
| `ResourceNotFoundException.java` | Added String identifier constructor |

## How to Run

### Prerequisites
- Docker Desktop running
- Java 21
- Maven

### Start Infrastructure
```bash
docker-compose up -d
```

### Run the Application
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Run Tests
```bash
mvn test
```

## Verification

### 1. Register a new user
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"johndoe","email":"john@example.com","password":"password123"}'
```

Expected response:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "johndoe",
  "email": "john@example.com",
  "role": "ROLE_CUSTOMER"
}
```

### 2. Login with existing user
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"johndoe","password":"password123"}'
```

### 3. Access protected endpoint without token (should fail)
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[]}'
```
Expected: `403 Forbidden`

### 4. Access protected endpoint with token (should succeed)
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-access-token>" \
  -d '{"customerId":1,"items":[]}'
```

### 5. Refresh an expiring token
```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Authorization: Bearer <your-refresh-token>"
```

### 6. Test admin-only endpoint
```bash
# As CUSTOMER (should fail with 403)
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <customer-token>" \
  -d '{"name":"Widget","price":29.99,"stockQuantity":100}'

# As ADMIN (should succeed with 201)
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <admin-token>" \
  -d '{"name":"Widget","price":29.99,"stockQuantity":100}'
```

## Tests

### Test Classes

| Test | Purpose |
|------|---------|
| `JwtServiceTest` | Unit tests for JWT token generation, validation, extraction |
| `AuthControllerIntegrationTest` | Integration tests for register, login, refresh endpoints |
| `SecurityIntegrationTest` | Integration tests for secured endpoints, role-based access |

### Running Security Tests
```bash
# Run all security-related tests
mvn test -Dtest=JwtServiceTest,AuthControllerIntegrationTest,SecurityIntegrationTest

# Run with Testcontainers (Docker required)
mvn test
```

## Architecture Decisions

### 1. JWT over Session-based Authentication
- **Stateless:** No server-side session storage required
- **Scalable:** Tokens can be validated without database lookups
- **Mobile-friendly:** Works well with mobile apps and SPAs

### 2. BCrypt Password Hashing
- **Salted hashes:** Each password has unique salt
- **Adaptive cost:** Can increase work factor as hardware improves
- **One-way:** Cannot reverse-engineer passwords from hashes

### 3. Role-based Access Control (RBAC)
- **Simple:** Two roles (CUSTOMER, ADMIN) for now
- **Extensible:** Easy to add new roles (MANAGER, SUPER_ADMIN, etc.)
- **Method-level:** Can use `@PreAuthorize` for fine-grained control

### 4. Separate Access and Refresh Tokens
- **Access token:** Short-lived (24 hours), used for API calls
- **Refresh token:** Long-lived (7 days), used to get new access tokens
- **Security:** Compromised access token expires quickly

## Security Considerations

### Production Checklist
- [ ] Change `oms.security.jwt.secret-key` to a strong, random value (at least 256 bits)
- [ ] Use HTTPS in production (JWT tokens are sent in clear text over HTTP)
- [ ] Implement token blacklisting for logout functionality
- [ ] Add rate limiting on auth endpoints to prevent brute-force attacks
- [ ] Consider adding account lockout after N failed login attempts
- [ ] Implement proper CORS configuration for frontend origins
- [ ] Add audit logging for authentication events

### Known Limitations
- No token blacklisting (tokens remain valid until expiration)
- No password reset functionality
- No email verification on registration
- No multi-factor authentication (MFA)

## Next Steps (Phase 4+)

- **Phase 4:** AOP, Async Processing, Caching & Scheduling
- **Phase 5:** Advanced security (OAuth2, OIDC, SAML)
- **Phase 6:** API rate limiting and throttling
- **Phase 7:** Audit logging and compliance
