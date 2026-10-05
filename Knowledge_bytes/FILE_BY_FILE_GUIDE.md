# Meridian — File-by-File Guide

> **Part A of the system knowledge package.**
> [`SYSTEM_ARCHITECTURE.md`](SYSTEM_ARCHITECTURE.md) — what the system is and why.
> [`SYSTEM_WORKFLOW.md`](SYSTEM_WORKFLOW.md) — how a request travels end to end.

This is the source-level tour: every meaningful file, what it is for, who calls it, what it
calls, and which system it touches. **76 Java files** under `backend-java/src/main/java/`,
**70 files** under `frontend/src/`.

Each entry follows a fixed shape:

```
Purpose      — what the file does
Used by      — who calls it
Calls        — what it calls
Database     — PostgreSQL / MongoDB / Elasticsearch / RabbitMQ / Filesystem / none
Key methods  — the methods that matter
```

---

## Contents

| § | Section |
|---|---|
| [0](#0-repository-root--what-is-actually-here) | Repository root — what is actually here (⚠ two backends) |
| [1](#1-java-package-structure) | Java package structure + every backend file |
| [2](#2-frontend-file-structure) | Frontend file structure + every frontend file |
| [3](#3-file--database-matrix) | File → database matrix |
| [4](#4-dead-code--unused-wiring) | Dead code and unused wiring |

---

# 0. Repository root — what is actually here

**Read this first.** The repository contains a *previous* implementation alongside the current
one, and the top-level `README.md` describes the previous one.

```
E-Commere Order Management and Search Service-Java/
├── backend-java/          ✅ THE RUNNING SERVICE — Spring Boot 3.2.5 / Java 21
│   ├── Dockerfile         (multi-stage: maven:3.9.6-eclipse-temurin-21 → temurin:21-jre-alpine)
│   └── src/main/java/…    76 Java files  ·  src/test → 0 files
│
├── backend/               ⚠ LEGACY — Python FastAPI service (51 .py files)
│   ├── app/api/…          auth, health, orders, products, search, users
│   ├── app/repositories/  postgres, mongo, elasticsearch
│   └── requirements.txt   ← NOT built by docker-compose.yml
│
├── frontend/              ✅ THE RUNNING FRONTEND — Vue 3 + Vite
├── docker-compose.yml     every `build.context` is ./backend-java or ./frontend
├── tests/, pytest.ini     ⚠ Python tests for the LEGACY backend (pytest not installed here)
├── README.md              ⚠ documents FastAPI + Swagger /docs + python -m pytest
├── JAVA_MIGRATION_MAP.md  ✅ describes the Python → Java migration
├── MIGRATION_REPORT.md    ✅ same
├── update_all_images.py   ⚠ out-of-band script (the image_url convention it defines is
│                            now implemented in SeedRunner — see SYSTEM_WORKFLOW.md Appendix B)
├── docs/
│   ├── MERIDIAN_COMPLETE_SYSTEM_KNOWLEDGE.md  (9,983 lines — documents the PYTHON service)
│   ├── FINAL_SYSTEM_AUDIT.md                  ✅ authoritative for the Java service
│   ├── SYSTEM_ARCHITECTURE.md                 ✅
│   ├── SYSTEM_WORKFLOW.md                     ✅
│   └── FILE_BY_FILE_GUIDE.md                  ✅ (this file)
│
├── and/, Management/, Order/, Search/
│   └── ⚠ EMPTY directories — artifacts of an unquoted `mkdir` of this repo's own path
│       ("E-Commere Order Management and Search Service-Java")
├── Knowledge bytes/Knowledge_Bytes.txt        ⚠ stray note
└── Service-Java/backend-java/src/…            ⚠ empty directory tree (0 files)
```

**So, concretely:**

* The **running** system is `backend-java/` + `frontend/` + `docker-compose.yml`.
* `README.md` will lead you to FastAPI and Swagger at `:8000/docs` — the Java service has **no**
  Swagger UI; its API surface is the table in
  [`SYSTEM_ARCHITECTURE.md` §8](SYSTEM_ARCHITECTURE.md#8-api-map).
* The legacy Python files are useful as a *reference* (the Java service was migrated from them)
  but nothing in `docker-compose.yml` executes them.

---

# 1. Java package structure

```
com/meridian/backend/          entry point, seeder, /api/health
├── auth/          login/logout/current-user endpoints + authentication logic
│   └── dto/       login/logout/user payloads
├── clients/       thin health-check wrappers over raw Mongo/ES/RabbitMQ clients
├── common/
│   ├── dto/       shared API payloads: error envelope, health, pagination, aggregations
│   └── util/      UTC date helpers, BigDecimal money rounding
├── config/        security chain, CORS/Jackson, Mongo, RabbitMQ, Elasticsearch
├── core/config/   env-var config holder + manual JPA/Hikari setup
├── exception/     AppException hierarchy + the @RestControllerAdvice that renders them
├── images/        product image upload/delete endpoints + filesystem storage
├── messaging/     RabbitMQ publisher (order changed) + listener (index into ES)
├── orders/        order endpoints + order domain service (PostgreSQL)
│   ├── dto/       order create/read/status payloads
│   ├── entity/    JPA entities: Order, OrderItem, User
│   └── repository/ Spring Data JPA repositories
├── products/      catalog endpoints + service (MongoDB)
│   ├── document/  Mongo @Document mapping
│   ├── dto/       product create/update/read/facets payloads
│   └── repository/ Spring Data MongoDB repository
├── search/        admin order-search endpoint + ES query builder
│   ├── dto/       search request/response + the ES order document
│   └── repository/ direct Elasticsearch client class
└── security/      JWT issue/validate, per-request filter, 401/403 handlers
```

---

## Root package

### `MeridianApplication.java`
**Purpose:** Spring Boot entry point; boots the app with Mongo auto-configuration excluded so
`MongoConfig` owns Mongo wiring entirely.
**Used by:** the JVM (`java -jar app.jar`).
**Calls:** Spring runtime.
**Database:** none directly.
**Key methods:** `main(String[])`.
Annotations: `@SpringBootApplication(exclude = MongoAutoConfiguration.class)`,
`@EnableAsync`, `@EnableScheduling` (note: no `@Async`/`@Scheduled` methods exist anywhere).

---

### `SeedRunner.java`
**Purpose:** the `seed`-profile seeder — wipes and refills users (PostgreSQL), products
(MongoDB) and orders (PostgreSQL), applies the price-rename snapshot demo, then rebuilds the
Elasticsearch index from scratch.
**Used by:** Spring Boot startup, **only** when `--spring.profiles.active=seed`.
**Calls:** `UserRepository`, `ProductRepository`, `OrderRepository`, `OrderSearchRepository`,
`PasswordEncoder`, `DateUtils`, `MoneyUtils`.
**Database:** PostgreSQL **+ MongoDB + Elasticsearch**.
**Key methods:**
- `run(ApplicationArguments)` — orchestrates `seedUsers → seedProducts → seedOrders →
  applySnapshotDemo → reindexSearch`
- `seedUsers()` — 8 customers + `admin@example.com`, BCrypt-hashed via `passwordEncoder.encode`
- `seedProducts()` — 27 `ProductDocument`s with tags/attributes/variants **and `image_url`**
  (`/products/<SKU>.svg`, `/products/default.svg` for `CHG-001`)
- `seedOrders()` — 48 orders + 107 items; **two-phase** save (`TMP-…` then `ORD-%06d`) because
  `order_number` is `NOT NULL` + unique and the id is only known after insert
- `applySnapshotDemo()` — renames `WM-001` to "Wireless Mouse Pro" and reprices it to ₹60,
  leaving historical order lines at "Wireless Mouse" / ₹50.16
- `reindexSearch()` — `searchRepository.recreateIndex()` **first** (order ids advance across
  seed runs, so stale docs would otherwise linger), then `bulkIndexOrders`
- `buildSearchDocument(Order)` — maps an order to `ElasticsearchOrderDocument` (mirrors
  `OrderSyncListener`)

---

### `HealthController.java`
**Purpose:** `GET /api/health` — pings all four dependencies and reports status + latency
(200 `ok` / 503 `degraded`).
**Used by:** browser and the Docker healthcheck.
**Calls:** `MongoClientWrapper`, `ElasticsearchClientWrapper`, `RabbitMQClient`, `AppConfig`.
**Database:** all four, read-only probes.
**Key methods:** `health()`, `checkPostgres()` (`connection.isValid(2)`), `checkMongo()`,
`checkElasticsearch()`, `checkRabbitMQ()`.

---

## `clients/`

### `MongoClientWrapper.java`
**Purpose:** health façade — `{ping:1}` against the `admin` database using the **shared**
`MongoClient`; deliberately never closes it.
**Used by:** `HealthController`. **Calls:** `MongoClient` bean from `MongoConfig`.
**Database:** MongoDB. **Key methods:** `ping()`, `getDatabase(String)`, `close()` (no-op).

### `ElasticsearchClientWrapper.java`
**Purpose:** builds its **own** low-level ES client from `AppConfig` (separate from the
`ElasticsearchConfig` data-access client) purely for health pings.
**Used by:** `HealthController`. **Calls:** `AppConfig`.
**Database:** Elasticsearch. **Key methods:** constructor, `ping()`, `getClient()`, `close()`.

### `RabbitMQClient.java`
**Purpose:** builds a raw AMQP `ConnectionFactory` from `AppConfig.getRabbitmqUrl()` and opens a
throw-away connection to prove reachability.
**Used by:** `HealthController`. **Calls:** `AppConfig`.
**Database:** RabbitMQ. **Key methods:** constructor, `ping()`.

---

## `common/dto/`

| File | Purpose | Used by | DB |
|---|---|---|---|
| `ErrorResponse.java` | the `{"error": {…}}` envelope | `GlobalExceptionHandler`, both security handlers | none |
| `ErrorBody.java` | `code` / `message` / `details` + nested `FieldError{field,message,type}` | `ErrorResponse`, exception handler | none |
| `HealthResponse.java` | `status`, `environment`, `dependencies{}` | `HealthController` | none |
| `HealthDependency.java` | one dependency's `status` + `latency_ms` | `HealthController` | none |
| `SearchAggregations.java` | `revenue` (sum) and `statusCounts` (terms) | `OrderSearchRepository.parseAggregations`, `SearchService` | none |
| `PaginationMeta.java` | `page/limit/total/pages` block | **nothing — dead code** (the response DTOs inline these fields) | none |

---

## `common/util/`

### `DateUtils.java`
**Purpose:** canonical UTC time helpers so seed, services and converters agree on the zone.
**Used by:** `SeedRunner`, `OrderService`, `ProductService`, `ImageService`, `OrderSyncListener`.
**Database:** none. **Key methods:** `utcNow()` (`OffsetDateTime.now(UTC)`), `toIsoString()`,
`startOfDay()`, `endOfDay()` (last three have no callers).

### `MoneyUtils.java`
**Purpose:** BigDecimal money rounding to 2 dp HALF_UP and order-total summation.
**Used by:** `OrderService` (`toMoney`, `calculateTotal`), `SeedRunner` (`toMoney`).
**Database:** none. **Key methods:** `toMoney(Object)`, `calculateTotal(List)`,
`calculateLineTotal()` (no external callers), `CENT`.

---

## `config/`

### `SecurityConfig.java`
**Purpose:** the entire HTTP authorization policy and authentication machinery.
**Used by:** Spring Security — runs on **every** HTTP request.
**Calls:** `JwtAuthenticationFilter`, `CustomAuthenticationEntryPoint`,
`CustomAccessDeniedHandler`, `UserRepository`, and the `CorsConfigurationSource` bean.
**Database:** PostgreSQL (via `UserRepository`).
**Key methods:**
- `securityFilterChain(HttpSecurity)` — CSRF off, `STATELESS`, CORS from the injected source,
  the URL rules listed in [`SYSTEM_ARCHITECTURE.md` §8](SYSTEM_ARCHITECTURE.md#8-api-map),
  custom 401/403 handlers, and `addFilterBefore(jwtAuthenticationFilter, …)`
- `userDetailsService()` — loads `User` **by email** into a Spring `User` with
  `roles(user.getRole())` → `ROLE_<role>`
- `authenticationProvider()` → `DaoAuthenticationProvider`
- `authenticationManager(AuthenticationConfiguration)`
- `passwordEncoder()` → `BCryptPasswordEncoder`
Annotations: `@EnableWebSecurity`, `@EnableMethodSecurity(prePostEnabled = true)`.

> **Why `@EnableMethodSecurity` matters:** it is what makes `@PreAuthorize("hasRole('ADMIN')")`
> effective — and those annotations are what actually protect product writes, because the URL
> `permitAll` lines for `/api/products` match first.

---

### `WebConfig.java`
**Purpose:** one CORS definition, the `/uploads/**` static mapping, and the app's
`ObjectMapper`.
**Used by:** Spring MVC and `SecurityConfig`.
**Calls:** none of ours. **Database:** Filesystem (serves `app.uploads.dir`).
**Key methods / beans:**
- `corsConfigurationSource()` — **`@Bean @Primary`**; `/**` → allowed origins from
  `app.cors.allowed-origins`, methods `GET,POST,PUT,PATCH,DELETE,OPTIONS`, headers `*`,
  credentials on, `maxAge 3600`. **This bean is the CORS fix** — without it Spring resolved
  `SecurityConfig`'s dependency by type to `HandlerMappingIntrospector`, which returns `null`
  and makes preflights fail (see [`SYSTEM_WORKFLOW.md` Appendix C](SYSTEM_WORKFLOW.md#appendix-c--admin-login-issue-diagnosis)).
- `corsFilter(CorsConfigurationSource)` — reuses the same source so the two cannot drift
- `objectMapper()` — `JavaTimeModule`, `write-dates-as-timestamps: false`,
  `PropertyNamingStrategies.SNAKE_CASE`
- `addResourceHandlers()` — `/uploads/**` → `file:${app.uploads.dir}/`

---

### `MongoConfig.java`
**Purpose:** manual Mongo wiring (auto-config is excluded) including **`OffsetDateTime ⇄ BSON
Date` conversion**.
**Used by:** Spring; `MongoClient` feeds `MongoClientWrapper`, `MongoTemplate` feeds
`ProductService`.
**Database:** MongoDB.
**Key methods / beans:**
- `mongoCustomConversions()` — `MongoCustomConversions` with `OffsetDateTimeToDateConverter`
  (`@WritingConverter`) and `DateToOffsetDateTimeConverter` (`@ReadingConverter`)
- `mongoClient()` — **`@Bean @Primary`**, `MongoClients.create(MongoClientSettings.builder()
  .applyConnectionString(new ConnectionString(mongoUri)).build())` — the single `MongoClient`
- `mongoDatabaseFactory(MongoClient)` → `SimpleMongoClientDatabaseFactory`
- `mongoTemplate(factory, conversions)` — passes the converter in explicitly so it is not
  dropped

> **Do not "simplify" this file.** The sanctioned fix is the `MongoCustomConversions` pair —
> a custom `CodecProvider`, a second `MongoClient`, or replacing `OffsetDateTime` fields with
> `Instant` are all explicitly out of bounds.

---

### `RabbitMQConfig.java`
**Purpose:** declares the sync topology and JSON conversion.
**Used by:** Spring; `RabbitTemplate` → `SyncPublisher`, topology → `OrderSyncListener`.
**Database:** RabbitMQ.
**Key methods:** `syncExchange()` (`orders.sync.exchange`, durable), `syncQueue()` (durable
`orders_sync`), `syncBinding(queue, exchange)` (routing key `orders.sync`),
`jsonMessageConverter()` (`Jackson2JsonMessageConverter`),
`rabbitTemplate(...)` (`mandatory = true`).
Constants: `SYNC_EXCHANGE`, `SYNC_QUEUE`, `SYNC_ROUTING_KEY`.

---

### `ElasticsearchConfig.java`
**Purpose:** builds the **primary** ES client stack used for indexing and searching, with
JSR-310 date support.
**Used by:** Spring; the `ElasticsearchClient` bean → `OrderSearchRepository`.
**Database:** Elasticsearch.
**Key methods:** `restClient()` (parses `spring.elasticsearch.uris`, optional basic auth,
timeouts), `elasticsearchTransport(RestClient)` — `RestClientTransport` + `JacksonJsonpMapper`
over an `ObjectMapper` **with `JavaTimeModule`** and ISO-8601 dates,
`elasticsearchClient(ElasticsearchTransport)`, `parseTimeout(String)`.

---

## `core/config/`

### `AppConfig.java`
**Purpose:** a flat `@Value` holder for every environment variable, mirroring the original
Python service's env contract.
**Used by:** `DatabaseConfig`, `HealthController`, `ElasticsearchClientWrapper`, `RabbitMQClient`.
**Database:** none (config only).
**Key methods:** `getPostgresJdbcUrl()`, `getRabbitmqUrl()` (falls back to
`amqp://user:pass@host:port/`), `getCorsOriginList()`, `getSyncTaskName()`, plus ~35 explicit
getters (`getElasticsearchIndex`, `getJwtSecretKey`, `isElasticsearchRefreshOnWrite`, …).

### `DatabaseConfig.java`
**Purpose:** hand-rolled JPA stack — Hikari pool, `EntityManagerFactory`, transaction manager,
repository scanning.
**Used by:** Spring; consumed by all JPA repositories and services.
**Database:** PostgreSQL.
**Key methods:** `dataSource()` (`HikariDataSource` from `AppConfig`),
`entityManagerFactory(DataSource)` (scans `orders.entity`, `PostgreSQLDialect`,
`hbm2ddl.auto=validate`, UTC), `transactionManager(EntityManagerFactory)`.
Annotations: `@EnableTransactionManagement`,
`@EnableJpaRepositories(basePackages = "…orders.repository")`.

---

## `auth/`

### `AuthController.java`
**Purpose:** HTTP surface for login/logout/current user under `/api/auth`.
**Used by:** the Vue frontend (public).
**Calls:** `AuthService`. **Database:** PostgreSQL (via the service).
**Key methods:** `login(@Valid LoginRequest)` → `POST /api/auth/login`;
`logout()` → `POST /api/auth/logout`; `me(@AuthenticationPrincipal User)` → `GET /api/auth/me`.

### `AuthService.java`
**Purpose:** credential verification and JWT issuance.
**Used by:** `AuthController`. **Calls:** `UserRepository`, `PasswordEncoder`,
`JwtTokenProvider`, `NotFoundException`, `UnprocessableException`.
**Database:** PostgreSQL.
**Key methods:**
- `authenticate(LoginRequest)` — `findByEmail` → reject empty hash → `passwordEncoder.matches`
  → `jwtTokenProvider.generateToken(id)` → `LoginResponse{access_token, "bearer", user}`
- `getCurrentUser(User)` → `UserResponse` — **NPEs if `user` is null**, which is why
  unauthenticated `GET /api/auth/me` returns 500
- `mapToUserResponse(User)` (private)

### `auth/dto/LoginRequest.java`
**Purpose:** login body. Fields: `email` (`@NotBlank @Email`), `password`
(`@NotBlank @Size(1..128)`).
**Note:** there is **no username field** — this is why `admin`/`admin` is rejected with 422.

### `auth/dto/LoginResponse.java` — `accessToken`, `tokenType` (default `"bearer"`), `user`.
### `auth/dto/LogoutResponse.java` — fixed `message: "Logged out successfully"` (stateless JWT;
nothing is revoked).
### `auth/dto/UserResponse.java` — `id`, `name`, `email`, `role`.
> A **second, duplicate** `UserResponse` exists as a nested class of `orders/dto/OrderResponse`.

---

## `exception/`

| File | HTTP | Thrown when |
|---|---|---|
| `AppException.java` | base class | carries `code`, `statusCode`, `details` for every typed error |
| `NotFoundException.java` | **404** | product/order/user not found |
| `ConflictException.java` | **409** | duplicate SKU |
| `UnprocessableException.java` | **422** | business rules: empty cart, quantity limit, inactive product, bad price, bad image, invalid credentials |
| `DependencyUnavailableException.java` | **503** | Elasticsearch unavailable (`search_unavailable`) |
| `OrderPersistenceException.java` | **500** | order write failed — *"No data was written"* |

### `GlobalExceptionHandler.java`
**Purpose:** `@RestControllerAdvice` translating every exception into the
`{"error":{code,message,details}}` envelope.
**Used by:** Spring MVC, on every failing request.
**Database:** none.
**Key methods:** `handleAppException` (uses the exception's own status),
`handleValidationException(MethodArgumentNotValidException)` → 422 with a `FieldError` list,
`handleConstraintViolation` → 422, `handleHttpMessageNotReadable` → 400,
`handleTypeMismatch` → 400, **`handleUnhandledException(Exception)` → 500 `internal_error`**.

> ⚠️ That last catch-all is the cause of two known caveats: it converts Spring Security's
> `AccessDeniedException` into **500 instead of 403/401**, and
> `HttpRequestMethodNotSupportedException` into **500 instead of 405**.

---

## `security/`

### `JwtTokenProvider.java`
**Purpose:** creates and validates the JWTs.
**Used by:** `AuthService` (issue), `JwtAuthenticationFilter` (validate).
**Database:** none. **Key methods:**
- `generateToken(String userId)` — `subject(userId)`, `issuedAt`, `expiration`,
  **`signWith(secretKey)`** — note it does *not* read `app.jwt.algorithm`, so jjwt infers the
  algorithm from key length and real tokens are `{"alg":"HS384"}`
- `validateToken(String)` — `parseSignedClaims`, `false` on any exception
- `getUserIdFromToken(String)` — returns `claims.getSubject()`

**Actual token contents:** `{"sub":"<user id>","iat":…,"exp":…}` — **no role, no email.**

### `JwtAuthenticationFilter.java`
**Purpose:** per-request Bearer-token authentication (`extends OncePerRequestFilter`).
**Used by:** the Spring Security filter chain (registered in `SecurityConfig`).
**Calls:** `JwtTokenProvider`, `UserRepository`.
**Database:** PostgreSQL (one lookup per authenticated request).
**Key method:** `doFilterInternal(...)` —
`Authorization: Bearer …` → validate → `getUserIdFromToken` → `userRepository.findById` →
grant **`SimpleGrantedAuthority("ROLE_" + role)`** → set the `SecurityContextHolder` → continue.
> The `ROLE_` prefix is precisely what makes `hasRole("ADMIN")` work.

### `CurrentUser.java`
**Purpose:** annotation alias for `@AuthenticationPrincipal` — injects the authenticated `User`
into a controller parameter. Used by `OrderController`, `ImageController`, `SearchController`.

### `CustomAuthenticationEntryPoint.java`
**Purpose:** renders **401** `{"error":{"code":"unauthorized","message":"Could not validate
credentials"}}` instead of the default redirect/blank. Used by `SecurityConfig`.

### `CustomAccessDeniedHandler.java`
**Purpose:** renders **403** `{"error":{"code":"forbidden","message":"Admin access required"}}`.
Used by `SecurityConfig`.

---

## `messaging/`

### `SyncPublisher.java`
**Purpose:** publishes "order changed" messages so the search projection gets rebuilt.
**Used by:** `OrderService.createOrder`, `OrderService.updateOrderStatus` (also injected, unused,
into `SearchService`).
**Calls:** `RabbitTemplate`. **Database:** RabbitMQ.
**Key methods:** `publishOrderSync(Integer orderId, String reason)` — `convertAndSend` to
`orders_sync`; **never throws** — returns `SyncInfo{enqueued:true, queue}` or
`SyncInfo{enqueued:false, error}`.
Nested: `OrderSyncMessage { orderId, reason }`.

### `OrderSyncListener.java`
**Purpose:** consumes sync messages and re-indexes the order into Elasticsearch, stamping
`search_indexed_at` back in PostgreSQL.
**Used by:** the RabbitMQ broker (consumer side of `orders_sync`).
**Calls:** `OrderRepository`, `OrderSearchRepository`, `DateUtils`.
**Database:** RabbitMQ **(in) + PostgreSQL (read + write) + Elasticsearch (write)**.
**Key methods:**
- `handleOrderSync(OrderSyncMessage)` — annotated
  `@RabbitListener(queues = "${app.rabbitmq.sync-queue:orders_sync}")`; logs celery-style
  start/failure and rethrows on error (manual ack + retry policy apply)
- `syncOrderDocument(Integer, String)` — `findByIdWithItemsAndUser` → `ensureIndex()` →
  `indexOrder()` → `orderRepository.updateSearchIndexedAt(...)`
- `buildOrderDocument(Order)` — same mapping as `SeedRunner.buildSearchDocument`

> Runs in **both** the `backend` and `java-worker` containers → competing consumers.

---

## `orders/`

### `OrderController.java`
**Purpose:** HTTP surface for order create/read/status under `/api/orders`.
**Used by:** the Vue frontend (authenticated).
**Calls:** `OrderService`, order DTOs. **Database:** PostgreSQL (via the service).
**Key methods:**
- `createOrder(OrderCreate, @CurrentUser User)` → `POST /api/orders`, returns **201**
- `getOrder(@PathVariable Integer, @CurrentUser User)` → `GET /api/orders/{id}`; **403** if a
  non-admin asks for someone else's order, **404** if it does not exist
- `updateStatus(orderId, StatusUpdateRequest)` → `PATCH /api/orders/{id}/status`,
  `@PreAuthorize("hasRole('ADMIN')")`

### `OrderService.java`
**Purpose:** order domain logic — cart resolution/pricing against the Mongo catalog, PostgreSQL
persistence with two-phase order-number generation, and sync publication.
**Used by:** `OrderController`. **Database:** PostgreSQL **+ MongoDB (pricing) + RabbitMQ
(publish)**.
**Key methods:**
- `@Transactional createOrder(...)` — forces `userId` from the principal → `resolveCart()` →
  save with `TMP-…` → re-save as `ORD-%06d` → save each `OrderItem` → on any non-`AppException`
  throw `OrderPersistenceException` (rollback) → **finally** `publishOrderSync(id,
  "order_created")`
- `getOrder(Integer)` — `findByIdWithItemsAndUser`, 404 if missing
- `@Transactional updateOrderStatus(Integer, String)` — flips status, stamps `updatedAt`,
  publishes `"status_update"`; an unchanged status returns `SyncInfo{queue:"in-process"}`
- `resolveCart(List<OrderItemCreate>)` (private) — merges duplicates, enforces
  `MAX_LINE_QUANTITY = 100`, `productRepository.findByIds`, rejects missing/inactive/invalid
  price, computes the total
- mapping helpers + inner types `ResolvedLine`, `ResolvedCart`
- `listUsers()` — **dead code**: no controller maps `GET /api/users`

### `orders/entity/Order.java`
**Table:** `orders` (indexes on unique `order_number`, `status`, `order_date`, `user_id`,
`(user_id, order_date)`).
**Fields:** `id`, `orderNumber`, `user` (`@ManyToOne LAZY`), `orderDate`, `status`
(default `"PENDING"`), `totalAmount`, `createdAt`, `updatedAt`, `searchIndexedAt`, `items`
(`@OneToMany(cascade=ALL, orphanRemoval=true)`, `@OrderBy("id ASC")`).
**Key methods:** `getCustomer()` (alias for `getUser()`), `recalculateTotal()` (2 dp HALF_UP).

### `orders/entity/OrderItem.java`
**Table:** `order_items` (indexes `order_id`, `product_id`).
**Fields:** `id`, `order` (`@ManyToOne LAZY`), `productId` (**the Mongo `_id`**, not the SKU),
`title` (**snapshot**), `quantity`, `unitPrice` (**snapshot**).
**Key methods:** `getLineTotal()` = `unitPrice × quantity`, 2 dp HALF_UP.

### `orders/entity/User.java`
**Table:** `users` (unique index on `email`).
**Fields:** `id`, `name`, `email`, `passwordHash`, `role`, `createdAt`, `orders`
(`@OneToMany`).
**Key methods:** none beyond accessors. Also consumed by `SecurityConfig.userDetailsService`,
`AuthService` and `JwtAuthenticationFilter`.

### `orders/repository/OrderRepository.java`
**Purpose:** JPA access to `orders`.
**Database:** PostgreSQL. **Key methods:** `findByIdWithItemsAndUser(id)` (`JOIN FETCH` items +
user — used everywhere a full order is needed), `updateSearchIndexedAt(orderId, indexedAt)`
(`@Modifying`), plus `countAllOrders`, `countAllOrderItems`, `findOrdersAfterId`,
`getStatusCounts` (**no callers**).

### `orders/repository/OrderItemRepository.java`
**Purpose:** `JpaRepository<OrderItem, Integer>` — the `save` used by `createOrder`.
`findByOrderId` has **no callers**. **Database:** PostgreSQL.

### `orders/repository/UserRepository.java`
**Purpose:** `JpaRepository<User, Integer>`.
**Key methods:** `findByEmail(String)` (login + `userDetailsService`),
`findAllOrderByIdAsc()` (only used by the dead `listUsers`).
**Used by:** `AuthService`, `JwtAuthenticationFilter`, `SecurityConfig`, `OrderService`,
`SeedRunner`. **Database:** PostgreSQL.

### `orders/dto/`
| File | Purpose |
|---|---|
| `OrderCreate.java` | request body: `userId` (`@Min(1)`, **overwritten server-side**), `items` (`@NotNull @Size(1..50)`) |
| `OrderItemCreate.java` | line: `productId` (`@NotBlank @Size(1..64)`), `quantity` (`@NotNull @Min(1) @Max(100)`) |
| `OrderResponse.java` | read model incl. nested `customer{id,name,email,role}` and `items[]` |
| `OrderCreateResponse.java` | order fields **+ `sync`** (`SyncInfo`), returned with 201 |
| `StatusUpdateResponse.java` | order fields **+ `sync`**, returned by the PATCH |
| `StatusUpdateRequest.java` | single field `status` (`@NotNull`) |
| `OrderItemResponse.java` | `id`, `productId`, `title`, `quantity`, `unitPrice`, `lineTotal` |
| `SyncInfo.java` | `{enqueued, taskId, queue, error}` — the RabbitMQ outcome block |

---

## `products/`

### `ProductController.java`
**Purpose:** HTTP surface for the catalog under `/api/products`.
**Used by:** the Vue frontend (reads public, writes ADMIN).
**Calls:** `ProductService`. **Database:** MongoDB (via the service).
**Key methods:**
- `listProducts(category, tags, q, visibility, page, limit, sort)` → `GET /api/products`
  (`visibility` regex `active|inactive|all`, `sort` regex `newest|title_asc|price_asc|price_desc`,
  `page ≤ 10000`, `limit ≤ 100`)
- `productFacets()` → `GET /api/products/facets`
- `getProduct(productId)` → `GET /api/products/{id}`
- `createProduct` → 201, `updateProduct` → PATCH, `deleteProduct` → DELETE —
  **all three carry `@PreAuthorize("hasRole('ADMIN')")`**

### `ProductService.java`
**Purpose:** catalog CRUD plus filtering/faceting directly against MongoDB via `MongoTemplate`.
**Used by:** `ProductController`, `ImageController`.
**Database:** MongoDB.
**Key methods:**
- `listProducts(...)` — builds `Criteria` (active flag, category exact, `tags $all`, `q` as a
  case-insensitive `$regex` over title/description/sku/tags), sort mapping, `mongoTemplate.count`
  + `find` → `ProductListResponse` with computed `pages`
- `listFacets()` — `Aggregation.facet()`: category counts + top-40 tag counts (active only)
- `getProduct(String)` — 404 `product_not_found`
- `createProduct(ProductCreate)` — duplicate-SKU check → `ConflictException` 409
- `updateProduct(String, ProductUpdate)` — partial `Update` via `mongoTemplate.updateFirst`,
  SKU uniqueness via `findBySkuAndIdNot`, price > 0 rule, stamps `updatedAt`
- `deleteProduct(String)` — **soft delete**: `active = false`
- `updateImageUrl` / `removeImageUrl` — keep `imageUrl` in sync after an upload
- `normalizeTags`, `toResponse`, variant converters; `ensureIndexes()` is an **empty uncalled
  stub**

### `products/document/ProductDocument.java`
**Collection:** `products`.
**Fields:** `_id`, `sku` (unique index `uniq_products_sku`), `title`, `description`, `price`,
`category` (`ix_products_category`), `tags[]`, `attributes{}`, `variants[{sku,color,stock}]`,
`active` (`ix_products_active`), **`image_url`**, `created_at`, `updated_at`.
Carries Lombok annotations **and** hand-written getters/setters **and** hand-written builders.
**Used by:** `ProductRepository`, `ProductService`, `ImageService`, `OrderService`, `SeedRunner`.

### `products/repository/ProductRepository.java`
**Purpose:** `MongoRepository<ProductDocument, String>`.
**Key methods:** `findBySku(String)`, `findBySkuAndIdNot(sku, id)`,
`findByIds(List<String>)` (`@Query { '_id': { $in: ?0 } }` — used by `OrderService.resolveCart`).
Unused: `findByActiveTrue`, `findByCategoryAndActiveTrue`, `findByTagsInAndActiveTrue`,
`countAll`.

### `products/dto/`
| File | Purpose |
|---|---|
| `ProductCreate.java` | admin create body: `sku` (`@NotBlank @Size(2..64)` + pattern), `title` (2..200), `price` (`0.01..1_000_000`), `category`, `tags`, `attributes`, `variants`, `active`, `imageUrl` |
| `ProductUpdate.java` | same fields, all-nullable partial update |
| `VariantDTO.java` | `{sku, color, stock}` with validation |
| `ProductResponse.java` | full read model incl. `image_url`, `updated_at`, `created_at`, `id` |
| `ProductListResponse.java` | `{items, page, limit, total, pages}` |
| `ProductFacetsResponse.java` | `{categories:[{value,count}], tags:[{value,count}]}` |

---

## `images/`

### `ImageController.java`
**Purpose:** multipart image endpoints on the `/api/products` path.
**Used by:** the Vue frontend (`ProductFormModal`), ADMIN.
**Calls:** `ImageService`, `ProductService`. **Database:** Filesystem + MongoDB.
**Key methods:** `uploadProductImage(productId, @RequestPart("file") MultipartFile, @CurrentUser)`
→ `POST /api/products/{id}/image` (consumes `multipart/form-data`);
`removeProductImage(...)` → `DELETE /api/products/{id}/image`.
Both carry `@PreAuthorize("hasRole('ADMIN')")`.

### `ImageService.java`
**Purpose:** validates, writes and deletes product image files, keeping `imageUrl` in sync.
**Used by:** `ImageController`. **Database:** Filesystem + MongoDB.
**Key methods:**
- `uploadImage(productId, file)` — 404 check → `validateImageFile` → `generateSafeFilename` →
  `Files.createDirectories` + `Files.copy` into `/app/uploads/products` → delete a replaced
  `/uploads/...` file → set `imageUrl = "/uploads/products/<uuid><ext>"` → save
- `removeImage(productId)` — delete the file, null `imageUrl`, save
- `validateImageFile` (private) — allow-list `image/jpeg|png|webp|svg+xml` →
  `invalid_image_format`; 5 MB cap → `image_too_large`
- `generateSafeFilename` (private) — UUID hex + allowed extension, fallback `.webp`

---

## `search/`

### `SearchController.java`
**Purpose:** the single admin-only search endpoint.
**Used by:** `AdminSearchView.vue`.
**Calls:** `SearchService`. **Database:** Elasticsearch (via the service).
**Key method:** `searchOrders(@Valid SearchRequest, @CurrentUser User)` →
`POST /api/search/orders`, `@PreAuthorize("hasRole('ADMIN')")`.

### `SearchService.java`
**Purpose:** translates admin criteria into an ES bool query, and the ES response into
`SearchResponse`.
**Used by:** `SearchController`. **Database:** Elasticsearch.
**Key methods:**
- `searchOrders(SearchRequest)` — `from/size`, sort `-order_date,-order_id`; a 400 from ES →
  `AppException(search_query_invalid, 400)`, anything else →
  `DependencyUnavailableException(search_unavailable, 503)`
- `buildQuery(SearchRequest)` — bool `must` (omni text) + filters (`status` terms,
  `order_date` range with `dateTo + 1 day`, `total_amount` range); **falls back to `matchAll`**
- `buildOmniQuery(String)` — **AND across terms** + phrase `multi_match` on
  `customer.name^3`/`customer.email` + uppercased `order_number` prefix,
  `minimumShouldMatch(1)`
- `termQuery(String)` — flat `multi_match` (`order_number^3`, `customer.name^3`), **nested**
  `multi_match` (`items.title^2`, `items.product_id`), and an `order_number` prefix
- `convertResponse`, `convertToSearchResult` (private)

### `search/repository/OrderSearchRepository.java`
**Purpose:** direct Elasticsearch client class (not Spring Data) — index lifecycle, indexing,
search with aggregations, response parsing.
**Used by:** `SearchService`, `OrderSyncListener`, `SeedRunner`.
**Database:** Elasticsearch.
**Key methods:**
- `ensureIndex()` — creates `orders` (1 shard / 0 replicas) with the full mapping including
  **`nested` items** and `search_as_you_type` customer/title fields
- `deleteIndex()`, `recreateIndex()`
- `indexOrder(doc)` — `_id = order_id`, `Refresh.True` when configured
- `bulkIndexOrders(List)` — used by the seeder
- `search(Query, from, size, sort)` — `trackTotalHits`, field sorts, plus aggregations
  `revenue` (`sum total_amount`) and `status_counts` (`terms status`, size 10)
- `parseAggregations` — seeds PENDING/PROCESSING/SHIPPED to `0` so KPI cards never go blank
- `countDocuments()` — **no callers**

### `search/dto/`
| File | Purpose |
|---|---|
| `SearchRequest.java` | `query (≤200)`, `status[]`, `dateFrom/dateTo (LocalDate)`, `minPrice/maxPrice`, `page (1..10000)`, `limit (1..100)` |
| `SearchResponse.java` | `{results, total, page, limit, pages, took_ms, aggregations}` |
| `SearchResult.java` | one hit + nested `SearchCustomer` / `SearchItem` |
| `ElasticsearchOrderDocument.java` | the JSON written to / read from ES; `@JsonProperty` forces snake_case (`order_id`, `order_number`, `order_date`, `updated_at`, `total_amount`, `product_id`, `unit_price`, `line_total`) |

---

# 2. Frontend file structure

```
frontend/src/
├── main.js               bootstrap: createApp → Pinia → router → mount
├── App.vue               <router-view/> + <ToastHost/>; calls authStore.init()
├── api/                  axios wrapper modules (one per resource)
├── router/index.js       route table, guards, titles, scrollBehavior
├── stores/               auth · cart · toast
├── layouts/              StorefrontLayout · AdminLayout
├── views/                6 route-level pages
├── components/
│   ├── common/           19 design-system primitives
│   ├── storefront/       8 store-page components
│   ├── checkout/         3 checkout sub-components
│   ├── auth/             login form
│   └── admin/            12 admin components
├── styles/               tokens.css · utilities.css
└── utils/                image · currency · dates · status · validation · debounce
```

## Entry & root

| File | Purpose | Used by |
|---|---|---|
| `main.js` | creates the app, installs `createPinia()` + router, imports both stylesheets, mounts `#app` | Vite |
| `App.vue` | renders `<router-view/>` + `<ToastHost/>`; runs `authStore.init()` on setup | `main.js`, smoke tests |

## `api/` — API modules

### `api/client.js`
**Purpose:** the single axios instance and all error handling. **Imported by every api module.**
**Key exports and behaviour:**
- `resolveBaseUrl()` — `VITE_API_BASE_URL`: `''` → relative (proxy/nginx);
  unset → `http://localhost:8000`; trailing slashes stripped. Exported as `API_BASE_URL`.
- `axios.create({ baseURL, timeout: 20000, paramsSerializer: { indexes: null } })`
  — the serializer emits repeated keys (`?tags=a&tags=b`)
- **request interceptor** — reads `localStorage['meridian.auth.token']` and sets
  `Authorization: Bearer …`
- **response interceptor** — unwraps `response.data`, and converts every failure via
  `toApiError()` into an `ApiError { code, status, details }`:
  envelope message → `code`; no response at all → `network_error` *"Cannot reach the API
  server…"*; otherwise `client_error`
- `fieldErrorsFrom(error)` — maps a 422's `details.fields[]` to `{field: message}`
- `messageFrom(error)` — best human-readable message

| Module | Export | Endpoint |
|---|---|---|
| `api/products.js` | `listProducts` | `GET /api/products` |
| | `getProductFacets` | `GET /api/products/facets` |
| | `getProduct` | `GET /api/products/{id}` |
| | `createProduct` | `POST /api/products` |
| | `updateProduct` | `PATCH /api/products/{id}` |
| | `deleteProduct` | `DELETE /api/products/{id}` (soft) |
| | `uploadProductImage` | `POST /api/products/{id}/image` (FormData) |
| | `removeProductImage` | `DELETE /api/products/{id}/image` |
| `api/orders.js` | `createOrder` | `POST /api/orders` — sends `{items:[{product_id, quantity}]}`, **no prices** |
| | `getOrder` | `GET /api/orders/{id}` |
| | `updateOrderStatus` | `PATCH /api/orders/{id}/status` |
| `api/search.js` | `searchOrders` | `POST /api/search/orders` |
| `api/users.js` | `listUsers` | `GET /api/users` — **imported by nothing (dead)** |

## `router/index.js`

**Purpose:** route table, guards, `document.title`, `scrollBehavior`.

| Path | Name | Component | meta |
|---|---|---|---|
| `/login` | `login` | `views/LoginView.vue` | `{ title:'Sign in', guest:true }` |
| `/` | — | `layouts/StorefrontLayout.vue` (eager) | — |
| `/` → `''` | `storefront` | `views/StorefrontView.vue` | `{ title:'Storefront' }` |
| `/` → `checkout` | `checkout` | `views/CheckoutView.vue` | `{ title:'Checkout', requiresAuth:true }` |
| `/admin` | — | `layouts/AdminLayout.vue` (eager) | `{ requiresAuth:true, requiresAdmin:true }` |
| `/admin` → `''` | — | redirect → `admin-search` | — |
| `/admin/search` | `admin-search` | `views/AdminSearchView.vue` | `{ title:'Search dashboard' }` |
| `/admin/orders/:id` | `order-details` | `views/OrderDetailsView.vue` | `{ title:'Order details' }` |
| `/admin/catalog` | `catalog-admin` | `views/CatalogAdminView.vue` | `{ title:'Catalog admin' }` |
| `/:pathMatch(.*)*` | — | redirect → `storefront` | — |

**Guard logic (verbatim):**

```js
router.beforeEach(async (to, from, next) => {
  const authStore = useAuthStore()
  if (!authStore.loading && authStore.token && !authStore.user) await authStore.fetchMe()

  const requiresAuth  = to.matched.some(r => r.meta.requiresAuth)
  const requiresAdmin = to.matched.some(r => r.meta.requiresAdmin)
  const isGuest       = to.matched.some(r => r.meta.guest)

  if (requiresAuth && !authStore.isAuthenticated)
    return next({ name: 'login', query: { redirect: to.fullPath } })
  if (requiresAdmin && !authStore.isAdmin) return next({ name: 'storefront' })
  if (isGuest && authStore.isAuthenticated) return next({ name: 'storefront' })
  next()
})
```

`scrollBehavior` returns `savedPosition`, or `false` when only `q/category/sort/page` changed
(so smooth-scroll to `#products-section` is not cancelled), else `{top:0}`.

## `stores/`

### `stores/auth.js`
**State:** `token`, `user`, `loading`, `error`.
**Persistence:** `localStorage['meridian.auth.token']` and `['meridian.auth.user']`.
**Getters:** `isAuthenticated` (`!!token && !!user`), `isAdmin` (`user?.role === 'ADMIN'`),
`userName`, `userEmail`, `userId`.
**Actions:**
- `login(email, password)` → `POST /api/auth/login` → store both keys →
  `router.push(query.redirect || '/')`; on error sets `error` and rethrows
- `logout()` → `POST /api/auth/logout` (errors ignored) → clear both keys → `/login`
- `fetchMe()` → no-op without a token; `GET /api/auth/me` → update `user`; any failure clears
  the session
- `init()` — called by `App.vue`; revalidates a restored session
**Used by:** `App.vue`, router guards, both layouts, `LoginForm`, `CheckoutView`.

> **`isAdmin` reads the `user` object from localStorage — it never decodes the JWT.**

### `stores/cart.js`
**State:** `items: { [productId]: { id, sku, title, price, category, image_url, quantity } }` —
a snapshot taken at add time (display only; the server re-prices).
**Getters:** `lines`, `isEmpty`, `itemCount`, `subtotal`.
**Actions:** `add(product, qty=1)` (merges; returns `null` if no `product.id`),
`setQuantity` (clamps 1..100 via `MIN_QTY`/`MAX_QTY`), `increment`, `decrement`, `remove`,
`clear()`.
**Persistence: none** — no `localStorage` write anywhere, so a reload empties the cart.

### `stores/toast.js`
**State:** `toasts[]` (capped at 5), a module-level id counter.
**Actions:** `push(type, message, timeout)`, `success`, `error` (6500 ms), `info`, `warning`,
`dismiss(id)`. Consumed by `ToastHost.vue`.

## `layouts/`

### `layouts/StorefrontLayout.vue`
**Purpose:** the public shell — announcement bar, sticky header with brand/nav/search,
account menu, cart button with count badge, mobile panel, `<main><router-view/>`, 3-column
footer, and the `<CartDrawer>`.
**Logic:** URL-driven search debounced 260 ms writing `?q=`; `/` and `Ctrl/Cmd+K` focus search,
`Esc` closes; `goCategory()` writes `?category=`; the **Admin** nav link renders only when
`authStore.isAdmin`; `logout()` → `authStore.logout()`.
**Children:** `AppIcon`, `CartDrawer`.

### `layouts/AdminLayout.vue`
**Purpose:** the admin shell — fixed sidebar (brand, "Search dashboard", "Catalog admin",
data-source note, "View storefront", user name/role + Logout), mobile overlay, sticky topbar
showing `route.meta.title`, content `<router-view/>`.
**Children:** `AppIcon`, `BaseButton`.

## `views/`

| View | Route | Responsibility | Children | API / stores |
|---|---|---|---|---|
| `StorefrontView.vue` | `/` | hero, category rail, tag marquee, active-filter chips, filters, grid, pagination. **The URL query (`q`,`category`,`sort`,`page`) is the single source of truth** | `HeroSection`, `CategorySection`, `TagMarquee`, `ProductFilters`, `ProductGrid`, `AppPagination` | `listProducts`, `getProductFacets`; cart, toast |
| `CheckoutView.vue` | `/checkout` | **live re-price** of every line via `Promise.allSettled(getProduct)`, marks unavailable rows, computes subtotal, `POST /api/orders`, clears cart, shows success | `CheckoutLineItems`, `OrderSummaryCard`, `OrderSuccessPanel`, `EmptyState` | `getProduct`, `createOrder`; cart, auth, toast |
| `LoginView.vue` | `/login` | branded card; on `@success` → `route.query.redirect \|\| '/'` | `LoginForm` | — |
| `AdminSearchView.vue` | `/admin/search` | KPIs, filters, count + "took X ms", error/empty/results states, desktop table + mobile cards, pagination; 350 ms debounce; filter changes reset to page 1 | `SearchKpis`, `SearchFilters`, `ResultsTable`, `ResultsCardList`, `AppPagination` | `searchOrders` |
| `OrderDetailsView.vue` | `/admin/orders/:id` | skeleton/404/error/loaded states; "Source of truth: PostgreSQL" badge, sync indicator, status control, Customer/Totals/Line-items cards | `OrderStatusControl`, `SearchSyncIndicator`, `AppCard`×3 | `getOrder`, `updateOrderStatus` |
| `CatalogAdminView.vue` | `/admin/catalog` | search box, category/visibility selects, New product, result meta, table/card states, pagination, create/edit modal, deactivate confirm | `CatalogTable`, `CatalogCardList`, `ProductFormModal`, `ConfirmDialog` | `listProducts`, `getProductFacets`, `updateProduct`, `deleteProduct` |

## `components/storefront/`

| File | Props | Emits | Notes |
|---|---|---|---|
| `HeroSection.vue` | — | — | fetches its own `listProducts({limit:5, sort:'newest'})`; featured + 2 previews; add-to-cart |
| `CategorySection.vue` | `categories[]`, `loading` | — | rail with counts; selection writes `?category=` |
| `TagMarquee.vue` | `tags[]`, `clickable`, `speed` | — | duplicated-track marquee; static under `prefers-reduced-motion` |
| `TagPill.vue` | `tag`, `clickable` | — | click → `?q=<tag>` |
| `ProductFilters.vue` | `category`, `sort`, `selectedTags`, `categoryFacets`, `tagFacets`, … | `update:category`, `update:sort`, `reset` | sort options `newest/title_asc/price_asc/price_desc`; **tag props declared but never rendered** |
| `ProductGrid.vue` | `items[]`, `loading`, `error` | `add`, `clear`, `retry` | 4-column grid with bespoke skeleton; **imports `LoadingSkeleton` but never uses it** |
| `ProductCard.vue` | `product` | `add(product)` | uses `ProductThumb`; "Add to cart" flips to "Added to cart" for 1400 ms |
| `CartDrawer.vue` | `open` | `close`, `checkout` | qty 1..100, remove, subtotal, checkout |

## `components/checkout/`

| File | Props | Emits | Notes |
|---|---|---|---|
| `CheckoutLineItems.vue` | `rows[]`, `loading` | — | unit price + line total + subtotal; "No longer available" badge |
| `OrderSummaryCard.vue` | `itemCount`, `subtotal`, `customer`, `placing`, `disabled` | `place` | **`CheckoutView` never passes `customer`** → renders "No customer selected" |
| `OrderSuccessPanel.vue` | `order` | `continue` | order number/total/status + the RabbitMQ sync alert (`order.sync.enqueued`); "View order details" routes to the **admin-only** `order-details` |

## `components/auth/`

### `LoginForm.vue`
**Props:** `redirect` (declared, **never read** — the store performs the redirect).
**Emits:** `success`.
**Behaviour:** client-side `validateEmail()` / `validatePassword()`, then
`authStore.login(email, password)`, showing `authStore.error`.
**Renders the demo hint:** `john.doe@example.com / john, admin@example.com / admin`.
**Children:** `FormField`, `BaseInput`, `BaseButton`.

## `components/admin/`

| File | Props | Emits | Used by |
|---|---|---|---|
| `SearchKpis.vue` | `aggregations`, `total`, `loading` | — | 5 `KpiCard`s: revenue, filtered, pending, processing, shipped (passes `tone="accent"`, for which no style is defined → neutral) |
| `SearchFilters.vue` | `query`, `statuses[]`, `dateFrom/To`, `minPrice/maxPrice`, `activeCount` | `update:*`, `reset` | status chips sorted `PENDING, PROCESSING, SHIPPED`, date/price ranges |
| `ResultsTable.vue` | `items[]`, `loading` | `select(id)` | desktop results; `order_number` is a `RouterLink` to `order-details` |
| `ResultsCardList.vue` | `items[]`, `loading` | `select(id)` | mobile results |
| `OrderStatusControl.vue` | `status`, `pending`, `updating` | `change(status)` | `BaseSelect` from `statusOptions()` |
| `SearchSyncIndicator.vue` | `indexedAt` | — | "Indexed" / "Not yet indexed" pill |
| `CatalogTable.vue` | `items[]`, `loading` | `edit`, `toggle` | image, SKU, title, price, category, tags, variants, active badge |
| `CatalogCardList.vue` | `items[]` | `edit`, `toggle` | mobile catalog |
| `ProductFormModal.vue` | `open`, `product` (null ⇒ create) | `close`, `saved(product, action)` | validates via `utils/validation`, uploads image (5 MB), maps 422 via `fieldErrorsFrom()` onto fields |
| `TagsInput.vue` | `modelValue[]`, `placeholder`, `invalid`, `max` | `update:modelValue` | Enter/comma commits, Backspace removes, lowercased + deduped |
| `AttributesEditor.vue` | `modelValue{}` | `update:modelValue` | key/value rows → object, coerces `"5"`/`"true"` |
| `VariantsEditor.vue` | `modelValue[]` | `update:modelValue` | rows `{sku, color, stock}` |

## `components/common/` — 19 primitives

`AppIcon` (~50 inline SVG names) · `BaseButton` · `BaseInput` · `BaseSelect` · `BaseTextarea` ·
`FormField` (provides the field id) · `AppAlert` · `AppBadge` · `StatusBadge` · `AppCard` ·
`AppModal` (Teleport + focus trap) · `AppDrawer` · `AppPagination` · `ConfirmDialog` ·
`EmptyState` · `KpiCard` · `LoadingSkeleton` · **`ProductThumb`** · `ToastHost`.

### `ProductThumb.vue` — the image component
```
imageUrl = resolveImageUrl(product.image_url)
<img v-if="showImage" :src="imageUrl" @load @error />
<div v-else class="thumb__placeholder">   monogram + deterministic pattern
```
`null`, a load error, or a failed load all fall back to the placeholder.
**Sizes:** `sm|md|lg`. **Used by:** `ProductCard`, `CartDrawer`, `HeroSection`,
`CheckoutLineItems`, `CatalogTable`, `CatalogCardList`.

## `utils/`

| File | Exports | Used by |
|---|---|---|
| `image.js` | `resolveImageUrl(value)` | `ProductThumb`, `ProductFormModal` |
| `currency.js` | `formatCurrency`, `formatNumber`, `parseAmount` (INR `en-IN`) | ~16 components |
| `dates.js` | `toUtcDate`, `formatDate/Time/DateTime`, `toDateInput`, `isValidDate` | order + catalog views |
| `status.js` | `ORDER_STATUSES = ['PENDING','PROCESSING','SHIPPED']`, `isOrderStatus`, `statusLabel/Tone/Options` | `StatusBadge`, `SearchFilters`, `OrderStatusControl` |
| `validation.js` | `SKU_PATTERN`, `validateSku/Title/Price/Category/Variant(s)/AttributeKeys`, `coerceAttributeValue` | product form |
| `debounce.js` | `debounce(fn, wait)` with `.cancel()` | storefront search, admin search, catalog |

### `resolveImageUrl` — exact logic

```js
1. non-string / empty          → null            → caller renders placeholder
2. /(^|[a-z][a-z0-9+.-]*:)\/\//i  or  /^(data|blob):/i
                              → returned untouched   (https://…, //cdn/…, previews)
3. startsWith('/uploads/') && API_BASE_URL
                              → API_BASE_URL + path  (uploads are served by the BACKEND)
4. anything else               → returned as-is      (bundled /products/<SKU>.svg assets)
```

## Styles & build

| File | Purpose |
|---|---|
| `styles/tokens.css` | `:root` design tokens (`--color-primary:#FFAC1C`, teal, surfaces, radii, shadows, spacing) + reset/base |
| `styles/utilities.css` | legacy alias tokens + shared utilities (`.tabular`, `.truncate`, `.sr-only`, `.only-mobile`) |
| `vite.config.js` | `host 0.0.0.0`, `port 5173`, proxies `/api` and `/uploads` → `VITE_PROXY_TARGET \|\| localhost:8000`; vitest config (`jsdom`, url `http://localhost:5173/` so CORS accepts test requests, `tests/**/*.spec.js`) |
| `package.json` | scripts `dev`/`build`/`preview`/`test`/`test:watch`; deps `axios pinia vue vue-router`; devDeps `@vitejs/plugin-vue @vue/test-utils jsdom vite vitest` |
| `nginx.conf` (prod) | proxies `/api/` and `/uploads/` → `http://backend:8000`, caches `/assets/` 7 days, SPA fallback |

**Tests (`frontend/tests/`, 5 files, 28 tests — all passing):**

| File | Covers |
|---|---|
| `smoke.spec.js` | full-app mounts against the **live API**: storefront cards, admin KPIs + ES results, empty checkout, live order details, catalog table. Logs in as `admin@example.com/admin` and seeds `localStorage` |
| `cart.spec.js` | add/merge/clamp/subtotal/remove/clear |
| `components.spec.js` | `BaseButton`, `StatusBadge`, `KpiCard` |
| `status.spec.js` | `utils/status` + `utils/dates` (UTC handling) |
| `currency.spec.js` | INR formatting, `formatNumber`, `parseAmount` |

---

# 3. File → database matrix

Legend: **W** = writes · **R** = reads · **P** = pings only · **·** = untouched.

| File | PostgreSQL | MongoDB | Elasticsearch | RabbitMQ | Filesystem |
|---|---|---|---|---|---|
| **`MeridianApplication.java`** | · | · | · | · | · |
| **`SeedRunner.java`** | W | W | W | · | · |
| **`HealthController.java`** | R | P | P | P | · |
| `clients/MongoClientWrapper` | · | P | · | · | · |
| `clients/ElasticsearchClientWrapper` | · | · | P | · | · |
| `clients/RabbitMQClient` | · | · | · | P | · |
| `common/util/DateUtils` | · | · | · | · | · |
| `common/util/MoneyUtils` | · | · | · | · | · |
| `config/SecurityConfig` | R | · | · | · | · |
| **`config/WebConfig`** | · | · | · | · | **W** (resource handler) |
| **`config/MongoConfig`** | · | **connection** | · | · | · |
| `config/RabbitMQConfig` | · | · | · | **topology** | · |
| `config/ElasticsearchConfig` | · | · | **connection** | · | · |
| `core/config/AppConfig` | · | · | · | · | · |
| `core/config/DatabaseConfig` | **connection** | · | · | · | · |
| **`auth/AuthController`** | via service | · | · | · | · |
| **`auth/AuthService`** | **R/W** | · | · | · | · |
| `exception/GlobalExceptionHandler` | · | · | · | · | · |
| **`security/JwtAuthenticationFilter`** | **R** | · | · | · | · |
| `security/JwtTokenProvider` | · | · | · | · | · |
| `security/CustomAuthenticationEntryPoint` | · | · | · | · | · |
| `security/CustomAccessDeniedHandler` | · | · | · | · | · |
| **`messaging/SyncPublisher`** | · | · | · | **W** | · |
| **`messaging/OrderSyncListener`** | **R/W** | · | **W** | **R** | · |
| **`orders/OrderController`** | via service | · | · | · | · |
| **`orders/OrderService`** | **W** | **R** (pricing) | · | **W** | · |
| `orders/entity/Order` | **table** | · | · | · | · |
| `orders/entity/OrderItem` | **table** | · | · | · | · |
| `orders/entity/User` | **table** | · | · | · | · |
| `orders/repository/OrderRepository` | **W** | · | · | · | · |
| `orders/repository/OrderItemRepository` | **W** | · | · | · | · |
| `orders/repository/UserRepository` | **R** | · | · | · | · |
| **`products/ProductController`** | · | via service | · | · | · |
| **`products/ProductService`** | · | **W** | · | · | · |
| `products/document/ProductDocument` | · | **collection** | · | · | · |
| `products/repository/ProductRepository` | · | **R/W** | · | · | · |
| **`images/ImageController`** | · | via service | · | · | via service |
| **`images/ImageService`** | · | **W** | · | · | **W** |
| **`search/SearchController`** | · | · | via service | · | · |
| **`search/SearchService`** | · | · | **R** | · | · |
| **`search/repository/OrderSearchRepository`** | · | · | **W/R** | · | · |
| `search/dto/ElasticsearchOrderDocument` | · | · | payload | · | · |
| — | | | | | |
| **FRONTEND** | | | | | |
| `api/orders.js` | **R/W** (via HTTP) | · | · | · | · |
| `api/products.js` | · | **R/W** | · | · | **W** (upload) |
| `api/search.js` | · | · | **R** | · | · |
| `api/client.js` | · | · | · | · | reads `localStorage` |
| `stores/auth.js` | · | · | · | · | `localStorage` |
| `stores/cart.js` | · | · | · | · | in-memory only |
| `utils/image.js` | · | · | · | · | resolves URLs |
| everything else | · | · | · | · | · |

---

# 4. Dead code and unused wiring

Discovered while inventorying. Harmless, but useful to know so you do not chase it.

**Backend**
* `orders/../users/` — `backend-java/src/main/java/com/meridian/backend/users/` is an **empty
  directory**: no package, no classes (the `users` concern lives in `orders/entity/User`).
* `OrderService.listUsers()` — no controller maps `GET /api/users`, yet `SecurityConfig`
  `permitAll`s that path.
* `ProductService.ensureIndexes()` — empty, never called.
* `PaginationMeta` — never referenced (response DTOs inline the fields).
* Unused repository methods: `OrderRepository.countAllOrders / countAllOrderItems /
  findOrdersAfterId / getStatusCounts`, `OrderItemRepository.findByOrderId`,
  `ProductRepository.findByActiveTrue / findByCategoryAndActiveTrue /
  findByTagsInAndActiveTrue / countAll`, `OrderSearchRepository.countDocuments`.
* Unused helpers: `DateUtils.toIsoString / startOfDay / endOfDay`,
  `MoneyUtils.calculateLineTotal / CENT`.
* `SyncPublisher` injected into `SearchService` but never used; `ErrorResponse` imported in four
  controllers without use.
* `@EnableAsync` / `@EnableScheduling` with no `@Async` / `@Scheduled` methods.
* `app.jwt.algorithm` declared in `application.yml` but never read.
* Duplicate `UserResponse` (top-level in `auth/dto` and nested in `orders/dto/OrderResponse`).

**Frontend**
* `api/users.js` — imported by nothing.
* `ProductGrid.vue` imports `LoadingSkeleton.vue` but uses its own skeleton markup.
* `ProductFilters.vue` declares tag props/emits the parent never passes and the template never
  renders.
* `LoginForm.vue`'s `redirect` prop is never read.
* `OrderSummaryCard.vue` accepts a `customer` prop `CheckoutView` never passes.
* `SearchKpis.vue` passes `tone="accent"`, but `KpiCard.vue` defines no `.kpi--accent` style.

---

*See also:* [`SYSTEM_ARCHITECTURE.md`](SYSTEM_ARCHITECTURE.md) ·
[`SYSTEM_WORKFLOW.md`](SYSTEM_WORKFLOW.md) ·
[`FINAL_SYSTEM_AUDIT.md`](FINAL_SYSTEM_AUDIT.md)
