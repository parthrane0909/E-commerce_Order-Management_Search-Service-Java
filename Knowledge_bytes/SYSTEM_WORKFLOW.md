# Meridian — System Workflow

> **Part A of the system knowledge package.**
> [`SYSTEM_ARCHITECTURE.md`](SYSTEM_ARCHITECTURE.md) — what the system *is* and why it is shaped
> that way. [`FILE_BY_FILE_GUIDE.md`](FILE_BY_FILE_GUIDE.md) — what every source file does.

This document traces **actual requests through actual classes**. Every class and method named
below exists in the source; nothing is hypothetical. Each flow is written as the stack trace of
a request:

```
Frontend → endpoint → controller → service → repository/client → database/system → response
```

---

## Contents

| Part | Section |
|---|---|
| **1** | [§4 — Complete request flow (A…R)](#4-complete-request-flow) |
| **2** | [§5 — Authentication & authorization](#5-authentication--authorization) |
| **3** | [§14 — Common debugging paths](#14-common-debugging-paths-if-x-is-broken-where-do-i-look) |
| **B** | [Appendix B — Image diagnosis](#appendix-b--image-issue-diagnosis) |
| **C** | [Appendix C — Admin login diagnosis](#appendix-c--admin-login-issue-diagnosis) |

---

## 4. Complete request flow

### A. Application startup

```
java -jar app.jar                          (Dockerfile ENTRYPOINT)
  └─ MeridianApplication.main()            @SpringBootApplication(exclude=MongoAutoConfiguration)
       │                                    @EnableAsync @EnableScheduling
       ├─ spring.properties → excludes spring Mongo auto-config entirely
       ├─ AppConfig          reads every env var (Postgres/Mongo/ES/Rabbit/JWT/uploads)
       ├─ DatabaseConfig     HikariDataSource → EntityManagerFactory (ddl-auto: validate)
       │                     → JpaTransactionManager → scans orders/repository
       ├─ MongoConfig        MongoClients.create(uri)  [SINGLE MongoClient bean]
       │                     + MongoCustomConversions(OffsetDateTime⇄Date)
       │                     → SimpleMongoClientDatabaseFactory → MongoTemplate
       ├─ ElasticsearchConfig RestClient → RestClientTransport(+JavaTimeModule mapper)
       │                     → ElasticsearchClient
       ├─ RabbitMQConfig     exchange orders.sync.exchange, queue orders_sync,
       │                     binding, Jackson2JsonMessageConverter, RabbitTemplate(mandatory)
       ├─ WebConfig          @Primary CorsConfigurationSource + CorsFilter
       │                     + ObjectMapper(SNAKE_CASE, JavaTimeModule)
       │                     + /uploads/** → file:/app/uploads/
       ├─ SecurityConfig     securityFilterChain (stateless, CSRF off, JWT filter),
       │                     BCryptPasswordEncoder, DaoAuthenticationProvider
       ├─ OrderSyncListener  @RabbitListener starts consuming orders_sync
       └─ SeedRunner         @Profile("seed") ONLY — runs on startup when that profile is on
            seedUsers → seedProducts → seedOrders → applySnapshotDemo → reindexSearch
```

**Ports:** backend binds `:8000`. Readiness is a `wget` spider of `/api/health`.

---

### B. Customer login

```
LoginForm.vue  (submit)
  │  client-side: validateEmail() → /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  │               validatePassword() → non-empty
  ▼
stores/auth.js → login(email, password)
  │  POST /api/auth/login     { "email": "...", "password": "..." }   ← snake_case not needed here
  │  Origin: http://localhost:5173
  │
  │  ① browser sends OPTIONS preflight (Content-Type: application/json is non-simple)
  │     → CorsFilter → CorsConfigurationSource → 200 + Access-Control-Allow-*     [was 403]
  │  ② real POST
  ▼
AuthController.login(@Valid LoginRequest)                     POST /api/auth/login
  │  bean validation first: @NotBlank @Email on `email`
  │  failure → 422 validation_error (GlobalExceptionHandler)
  ▼
AuthService.authenticate(request)
  │  userRepository.findByEmail(email)
  │    ├─ missing        → UnprocessableException (422)
  │    ├─ empty hash     → UnprocessableException (422)
  │    └─ found
  │  passwordEncoder.matches(raw, $2a$10$...)     ← BCrypt
  │    └─ false → UnprocessableException (422)
  │  jwtTokenProvider.generateToken(String.valueOf(user.getId()))
  ▼
LoginResponse { access_token, token_type:"bearer", user{id,name,email,role} }
  ▼
auth store: localStorage['meridian.auth.token'], ['meridian.auth.user']
  → router.push(redirect || '/')
```

---

### C. Admin login

**Identical to [§B](#b-customer-login)** — the code path has no branch on role. The only
differences are the credentials and what happens next:

* identity is an **email**: `admin@example.com`, not `admin` (see [Appendix C](#appendix-c--admin-login-issue-diagnosis))
* `user.role == "ADMIN"` is stored in `localStorage` → `authStore.isAdmin`
* the router guard then permits `/admin/**` instead of bouncing to the storefront

```js
isAdmin: (state) => state.user?.role === 'ADMIN'
```

---

### D. Storefront product loading

```
StorefrontView.vue (watch on route query q/category/sort/page)
  │
  ├─ listProducts({page, limit, sort, q, category, visibility})
  │    frontend/src/api/products.js → GET /api/products?...
  ▼
ProductController.listProducts(...)                      GET /api/products      [public]
  │  params: category, tags, q, visibility(active|inactive|all),
  │          page(≤10000), limit(≤100), sort(newest|title_asc|price_asc|price_desc)
  ▼
ProductService.listProducts(...)
  │  Criteria: active flag, category =, tags $all, q → $regex (case-insensitive
  │            over title|description|sku|tags)
  │  Sort mapping, mongoTemplate.count() + mongoTemplate.find()
  │  → ProductListResponse { items, page, limit, total, pages }
  ▼
MongoDB  catalog.products
  ▲
  │  GET /api/products/facets → ProductService.listFacets()
  │     Aggregation.facet(): category counts + top-40 tag counts (active only)
  ▼
ProductGrid.vue → ProductCard.vue → ProductThumb.vue   (renders image_url or placeholder)
```

---

### E. Product search (storefront)

The storefront "search" is **MongoDB text filtering**, not Elasticsearch:

```
StorefrontLayout.vue  (debounced 260 ms, keys / and Ctrl/Cmd+K)
  → writes ?q= into the URL (URL is the single source of truth)
  → StorefrontView re-runs  D  above
  → ProductService: $regex over title|description|sku|tags
```

**Elasticsearch search is a separate, admin-only feature** — flow [L](#l-admin-search).

---

### F. Add to cart

```
ProductCard.vue / HeroSection.vue  "Add to cart"
  → useCartStore().add(product, qty)
  ▼
stores/cart.js
  items[product.id] = { id, sku, title, price, category, image_url, quantity }
  │   • snapshot of title/price taken NOW (display only)
  │   • merge if the line exists, clamp quantity to 1..100 (MIN_QTY/MAX_QTY)
  │   • NOT persisted — reloading empties the cart
  ▼
CartDrawer.vue (opened by StorefrontLayout cart button)
     qty −/input/+, remove, subtotal, "Proceed to checkout"
```

> No HTTP request happens here. The cart is purely client-side until checkout.

---

### G. Checkout

```
CheckoutView.vue (on mount)
  │  Promise.allSettled(cart.lines.map(l => getProduct(l.id)))   ← LIVE re-price
  │    └─ a line that404s or fails is marked "No longer available" and cannot be ordered
  │  subtotal recomputed from the LIVE prices
  ▼
"Place order"  →  createOrder({ items: [{ product_id, quantity }] })
     NOTE: no price and no title are sent — the server re-prices everything
```

---

### H. Order creation → I. PostgreSQL transaction

Both steps happen inside one `@Transactional` method: `OrderService.createOrder()`.

```
frontend/src/api/orders.js → POST /api/orders        (Authorization: Bearer …)
  ▼
OrderController.createOrder(@Valid OrderCreate, @CurrentUser User)   [authenticated]
  │   → 201 Created
  ▼
OrderService.createOrder(payload, user)          @Transactional
  │
  │  1. userId is FORCED from the principal — the payload's userId is ignored
  │  2. resolveCart(payload.items)                       [reads MongoDB]
  │       • merge duplicate product lines
  │       • enforce MAX_LINE_QUANTITY = 100
  │       • productRepository.findByIds(ids)             ← Mongo _id list
  │       • reject: missing / inactive / price out of 0.01..1_000_000 → 422
  │       • MoneyUtils.calculateTotal()  (BigDecimal, 2dp HALF_UP)
  │
  │  3. order = new Order(); order.setOrderNumber("TMP-" + UUID…)   ← provisional
  │     orderRepository.save(order)                      ← FIRST INSERT
  │     order.setOrderNumber(String.format("ORD-%06d", order.getId()))  ← final
  │     orderRepository.save(order)                      ← UPDATE same row
  │     (two-phase because order_number is NOT NULL + UNIQUE and the id is only
  │      known after insert; both saves are in ONE transaction)
  │
  │  4. for each resolved line: new OrderItem(productId, title, unitPrice, qty)
  │     order.getItems().add(item)   → cascade ALL persists the lines
  │     order.recalculateTotal()
  │
  │  5. any non-AppException → OrderPersistenceException("No data was written") → ROLLBACK
  │
  │  └─ finally: syncPublisher.publishOrderSync(id, "order_created")   [after commit]
  ▼
OrderCreateResponse { …order…, sync: { enqueued, queue, error } }
```

**PostgreSQL transaction boundary (flow I):**

```
BEGIN
  INSERT orders            (order_number 'TMP-…', user_id, order_date, status, total)
  UPDATE orders            (order_number 'ORD-%06d')
  INSERT order_items × N   (product_id, title snapshot, quantity, unit_price)
  UPDATE orders            (total_amount, updated_at)
COMMIT                     ← only now is the order durable
-- then, outside the transaction:
publish → RabbitMQ
```

If the broker is unreachable, **the order still exists** — `SyncInfo.enqueued=false` with the
error is returned in the response body rather than failing checkout.

---

### J. RabbitMQ synchronization

```
SyncPublisher.publishOrderSync(orderId, reason)                 messaging/SyncPublisher.java
  │  RabbitTemplate.convertAndSend("orders_sync", OrderSyncMessage{orderId, reason})
  │  RabbitTemplate is built with mandatory=true + a Jackson2JsonMessageConverter
  │  failures are caught and returned as SyncInfo{enqueued:false, error}
  ▼
RabbitMQConfig topology:
  exchange  orders.sync.exchange   (topic, durable)
  queue     orders_sync            (durable)
  binding   orders.sync.exchange → orders_sync, routing key "orders.sync"
```

`OrderSuccessPanel.vue` surfaces the `sync.enqueued` flag to the user.

---

### K. Java worker → Elasticsearch

```
RabbitMQ  orders_sync
  ▼
OrderSyncListener.handleOrderSync(OrderSyncMessage)      @RabbitListener
  │   manual ack · prefetch 1 · concurrency 2
  │   retry: 5 attempts, 10s initial, ×2, max 120s
  │   default-requeue-rejected = false   ← a poison message is NOT requeued forever
  ▼
syncOrderDocument(orderId, reason)                       (private)
  │  1. orderRepository.findByIdWithItemsAndUser(orderId)
  │        JOIN FETCH items + user      ← ONE query, no N+1
  │  2. orderSearchRepository.ensureIndex()      ← create orders index if absent
  │  3. doc = buildOrderDocument(order)          ← same mapping as SeedRunner
  │  4. orderSearchRepository.indexOrder(doc)
  │        _id = order_id, Refresh.True when app.elasticsearch.refresh-on-write=true
  │  5. orderRepository.updateSearchIndexedAt(orderId, now)   ← stamps Postgres
  ▼
Elasticsearch index `orders`
  ▲
  └─ OrderDetailsView → SearchSyncIndicator shows "Indexed" / "Not yet indexed"
```

> **Important:** `java-worker` and `backend` both run this listener — competing consumers.
> Additionally, the **seeder bypasses this path entirely** (`SeedRunner.reindexSearch()` does
> `recreateIndex()` + `bulkIndexOrders()` directly).

---

### L. Admin search

```
AdminSearchView.vue  (350 ms debounced; any filter change resets page to 1)
  ▼
frontend/src/api/search.js → POST /api/search/orders
   { query, status[], date_from, date_to, min_price, max_price, page, limit }
  ▼
SearchController.searchOrders(@Valid SearchRequest, @CurrentUser User)
        @PreAuthorize("hasRole('ADMIN')")         + URL rule /api/search/** = ADMIN
  ▼
SearchService.searchOrders(request)
  │  buildQuery(request)
  │    ├─ text?  buildOmniQuery(text): term-AND across terms
  │    │         + phrase multi_match on customer.name^3 / customer.email
  │    │         + order_number prefix (uppercased)
  │    │         minimumShouldMatch(1)
  │    │         └─ per term: termQuery() → flat multi_match (order_number^3,
  │    │            customer.name^3) | NESTED multi_match (items.title^2,
  │    │            items.product_id) | order_number prefix
  │    ├─ status[]        → terms filter
  │    ├─ date_from/to    → range order_date (to is +1 day, inclusive)
  │    ├─ min/max_price   → range total_amount
  │    └─ nothing at all  → match_all
  │  from = (page-1)*limit, size = limit, trackTotalHits: true
  │  sort = -order_date, -order_id
  ▼
OrderSearchRepository.search(query, from, size, sort)
  │  + aggregations: sum(total_amount) → revenue
  │                  terms(status, 10) → status_counts   (PENDING/PROCESSING/SHIPPED seeded 0)
  ▼
Elasticsearch  index orders
  ▲
  SearchResponse { results[], total, page, limit, pages, took_ms, aggregations }
  ▼
SearchKpis (revenue + counts) · SearchFilters · ResultsTable / ResultsCardList · AppPagination
```

---

### M. Admin order details

```
Route /admin/orders/:id  →  OrderDetailsView.vue
  ▼
frontend/src/api/orders.js → GET /api/orders/{id}          Authorization: Bearer …
  ▼
OrderController.getOrder(@PathVariable Integer, @CurrentUser User)
  │   • OrderService.getOrder()  → 404 order_not_found
  │   • non-ADMIN asking for someone else's order → 403
  ▼
OrderService.getOrder(id)
  │  orderRepository.findByIdWithItemsAndUser(id)     ← Postgres, JOIN FETCH
  ▼
OrderResponse { id, order_number, customer{id,name,email,role}, order_date,
                status, total_amount, updated_at, search_indexed_at, items[] }
  ▼
"Source of truth: PostgreSQL" badge · OrderStatusControl · SearchSyncIndicator
Customer / Totals / Line-items cards
```

---

### N. Customer order details

**The same endpoint and the same code path as [M](#m-admin-order-details)** — there is no
separate customer order API.

The difference is purely authorization: a `CUSTOMER` may read only their own order
(`OrderController.getOrder` compares `order.getUser().getId()` with the principal and returns
**403** on a mismatch; a missing id returns **404**). Verified: `john.doe` reading an order
owned by `riley.brooks` → `403`; admin → `200`; no token → `401`.

> Note the frontend only *routes* to `/admin/orders/:id`, so order details are reachable from
> the admin area.

---

### O. Admin status update

```
OrderStatusControl.vue (BaseSelect: PENDING / PROCESSING / SHIPPED)
  ▼
frontend/src/api/orders.js → PATCH /api/orders/{id}/status  { status }
  ▼
OrderController.updateStatus(...)   @PreAuthorize("hasRole('ADMIN')")
                                    + URL rule PATCH /api/orders/**/status = ADMIN
  ▼
OrderService.updateOrderStatus(id, status)          @Transactional
  │  load → 404 if missing
  │  order.setStatus(status); order.setUpdatedAt(now)
  │  commit
  │  finally: syncPublisher.publishOrderSync(id, "status_update")
  │     • unchanged status → SyncInfo{queue:"in-process"} (no message published)
  ▼
StatusUpdateResponse { …order…, sync }
  → OrderDetailsView updates the badge + toast
  → RabbitMQ → worker → Elasticsearch re-index (flow K)
```

---

### P. Catalog administration

```
CatalogAdminView.vue
  │  list:      listProducts({page,limit,sort,q,category,visibility})
  │  facets:    getProductFacets()
  ▼
READ    GET    /api/products            → ProductService.listProducts   [public]
        GET    /api/products/facets     → ProductService.listFacets     [public]
CREATE  POST   /api/products            → @PreAuthorize ADMIN → createProduct
        │    duplicate sku → ConflictException 409
        │    price ≤ 0 or > 1_000_000 → 422
        │    normalizeTags() → productRepository.save()
UPDATE  PATCH  /api/products/{id}       → @PreAuthorize ADMIN → updateProduct
        │    partial Update via mongoTemplate.updateFirst
        │    sku uniqueness via findBySkuAndIdNot → 409
        │    stamps updated_at
DELETE  DELETE /api/products/{id}       → @PreAuthorize ADMIN → deleteProduct
           SOFT delete: active = false   (history and search results stay intact)
  ▼
MongoDB  catalog.products
```

`ProductFormModal.vue` validates first (`utils/validation.js`) and maps a 422 back onto fields
via `fieldErrorsFrom(error)`.

---

### Q. Product image upload

```
ProductFormModal.vue  →  uploadProductImage(id, file)
   frontend/src/api/products.js
   POST /api/products/{id}/image   Content-Type: multipart/form-data, field "file"
  ▼
ImageController.uploadProductImage(...)   @PreAuthorize ADMIN
  ▼
ImageService.uploadImage(productId, file)
  │  1. productRepository.findById → 404 product_not_found
  │  2. validateImageFile(): type ∈ {image/jpeg, image/png, image/webp, image/svg+xml}
  │                         ext  ∈ {.jpg .jpeg .png .webp .svg}
  │                         size ≤ 5 MB      → 422 invalid_image_format / image_too_large
  │  3. filename = UUID hex + allowed extension (fallback .webp)
  │  4. Files.createDirectories(/app/uploads/products); Files.copy(...)   ← Filesystem
  │  5. if a previous /uploads/... file exists, delete it
  │  6. product.setImageUrl("/uploads/products/<uuid><ext>"); save()        ← MongoDB
  ▼
ProductResponse (refreshed) → modal preview
```

---

### R. Product image display

```
ProductThumb.vue
  imageUrl = resolveImageUrl(product.image_url)          utils/image.js
     │
     ├─ null / empty ───────────────────────────► placeholder (monogram + pattern)
     ├─ https://…  |  //cdn/…  |  data: | blob: ─► returned untouched
     ├─ starts with "/uploads/"  ───────────────► API_BASE_URL + path
     │     (uploads are served by the BACKEND)
     └─ anything else, e.g. "/products/WM-001.svg" ► returned as-is
                                                   (bundled ARTWORK, served by the FRONTEND)

<img v-if="showImage" :src="imageUrl" @load="onLoad" @error="onError" />
   @error → failed = true → falls back to placeholder
```

**Who serves which file:**

| `image_url` value | Served by | Verified response |
|---|---|---|
| `/products/WM-001.svg` | frontend `:5173` (Vite/nginx static) | `200 image/svg+xml` 1578 B |
| `/uploads/products/<uuid>.png` | backend `:8000` via `WebConfig` resource handler `/uploads/**` → `file:/app/uploads/` | *(volume is currently empty)* |

See [Appendix B](#appendix-b--image-issue-diagnosis) for the diagnosis of the image bug.

---

## 5. Authentication & authorization

### Login endpoint

`POST /api/auth/login` with `{ "email": "<email>", "password": "<pw>" }`.
There is **no username field** — `LoginRequest` declares `@NotBlank @Email private String email`,
and the `users` table has **no `username` column** (columns: `id, name, email, password_hash,
role, created_at`).

### Password hashing — BCrypt

* `SecurityConfig.passwordEncoder()` → `BCryptPasswordEncoder`.
* Seeded hashes are `$2a$10$…`, **60 characters** — a genuine BCrypt hash.
* `AuthService.authenticate()` calls `passwordEncoder.matches(raw, stored)`.
* Seed writes hashes via `passwordEncoder.encode(...)` (it once wrote plaintext — fixed; see the
  audit).

### JWT creation

```java
// JwtTokenProvider.generateToken(String userId)
Jwts.builder().subject(userId).issuedAt(now).expiration(now + 1440min)
     .signWith(secretKey)          // secretKey = Keys.hmacShaKeyFor(app.jwt.secret)
     .compact();
```

Observed token (decoded):

```json
header  {"alg":"HS384"}
payload {"sub":"477","iat":1791175280,"exp":1791261680}     // 1440 minutes
```

**JWT contents:** `sub` = the **user id as a string**, `iat`, `exp`. Nothing else.

> Two things worth knowing:
> * **There is no role claim and no email claim.** The role is fetched from PostgreSQL on every
>   request (below).
> * `app.jwt.algorithm: HS256` is declared in `application.yml` but **never read** — the code
>   calls `signWith(secretKey)`, and jjwt picks the algorithm from the key length, so real
>   tokens are `HS384`.

### JWT validation & the filter

```
JwtAuthenticationFilter  (extends OncePerRequestFilter, registered BEFORE
                          UsernamePasswordAuthenticationFilter)
  1. read header Authorization: "Bearer <token>"      (absent → continue unauthenticated)
  2. jwtTokenProvider.validateToken(token)           (parseSignedClaims; any exception → false)
  3. userId = jwtTokenProvider.getUserIdFromToken()  (claims.getSubject())
  4. userRepository.findById(userId)                 ← PostgreSQL on EVERY request
       • token valid but user gone → not authenticated
  5. authority = "ROLE_" + user.getRole()             ← THIS is what makes hasRole() work
  6. SecurityContextHolder.setRequestAuthentication(…)
  7. chain.doFilter(...)
```

Step 5 is the crux: `SimpleGrantedAuthority("ROLE_" + "ADMIN")` → `ROLE_ADMIN` →
`hasRole("ADMIN")` matches. Without the `ROLE_` prefix, every `hasRole("ADMIN")` would fail.

### Authorities and the two roles

| Role | Authority granted | Can do |
|---|---|---|
| `CUSTOMER` | `ROLE_CUSTOMER` | browse, checkout, read **own** orders |
| `ADMIN` | `ROLE_ADMIN` | everything above + search, status changes, catalog writes, image upload, read any order |

### Protected routes

Declared in `SecurityConfig.securityFilterChain` — see
[`SYSTEM_ARCHITECTURE.md` §8](SYSTEM_ARCHITECTURE.md#8-api-map) for the exact list and the
shadowing note. Enforcement actually happens in **two places**:

1. **URL rules** (`AuthorizationFilter`) — effective for `/api/search/**`,
   `PATCH /api/orders/**/status`, and `POST|GET /api/orders`.
2. **Method-level `@PreAuthorize("hasRole('ADMIN')")`** — effective for product writes and
   image upload/delete, because the URL `permitAll` lines for `/api/products` match first.

### Backend authorization checks

* **Admin-only operations:** `@PreAuthorize` on `SearchController.searchOrders`,
  `OrderController.updateStatus`, `ProductController.create/update/deleteProduct`,
  `ImageController.upload/removeProductImage`.
* **Customer ownership:** `OrderController.getOrder` — non-admin requesting another user's
  order → **403**; missing id → **404**.
* **Server-side identity override:** `OrderService.createOrder` forces `userId` from the
  principal, so a client cannot place an order as someone else.
* **401 / 403 bodies:** `CustomAuthenticationEntryPoint` → `{"error":{"code":"unauthorized",
  "message":"Could not validate credentials"}}`; `CustomAccessDeniedHandler` →
  `{"error":{"code":"forbidden","message":"Admin access required"}}`.

### How `admin/admin` is *supposed* to work

Based strictly on the current implementation:

| You type | What happens |
|---|---|
| `admin` / `admin` | **422** `validation_error` — `email must be a well-formed email address`. Never reaches password checking. |
| `admin@example.com` / `admin` | **200** + JWT with `role: "ADMIN"` ✅ |
| `admin@example.com` / wrong password | 422 (invalid credentials) |

So "`admin/admin`" is a shorthand for "`admin@example.com` / `admin`". The login form itself
says so — `LoginForm.vue` renders the hint:

```
Demo accounts: john.doe@example.com / john, admin@example.com / admin
```

and the field is labelled **Email** with placeholder `you@example.com`.

### Seeded customer accounts

From `SeedRunner.seedUsers()` — 9 users in PostgreSQL, all BCrypt-hashed:

| id | name | email | password | role |
|---|---|---|---|---|
| +0 | John Doe | `john.doe@example.com` | `john` | CUSTOMER |
| +1 | Jane Smith | `jane.smith@example.com` | `jane` | CUSTOMER |
| +2 | Wendy Wireless | `wendy.wireless@example.com` | `wendy` | CUSTOMER |
| +3 | Alex Rivera | `alex.rivera@example.com` | `alex` | CUSTOMER |
| +4 | Sam Patel | `sam.patel@example.com` | `sam` | CUSTOMER |
| +5 | Casey Nguyen | `casey.nguyen@example.com` | `casey` | CUSTOMER |
| +6 | Morgan Lee | `morgan.lee@example.com` | `morgan` | CUSTOMER |
| +7 | Riley Brooks | `riley.brooks@example.com` | `riley` | CUSTOMER |
| +8 | **Admin** | **`admin@example.com`** | **`admin`** | **ADMIN** |

(Customer ids start at 460 in the first seed run and advance on every subsequent run.)

### Logout behavior

`POST /api/auth/logout` returns a fixed `{"message":"Logged out successfully"}`. Because the
session is **stateless JWT**, **nothing is revoked server-side** — the token remains valid until
`exp` (24 h). The frontend discards `localStorage['meridian.auth.token'/'meridian.auth.user']`,
which is what actually ends the session *in that browser*. Anyone holding a copy of the token
can still use it.

---

## 14. Common debugging paths (if X is broken, where do I look?)

### Products don't appear

```
MongoDB catalog.products  →  countDocuments({active:true})
  → ProductRepository (products/repository/)
  → ProductService.listProducts()          ← Criteria, visibility/sort/paging
  → ProductController.listProducts()       GET /api/products
  → curl http://localhost:8000/api/products?limit=5      (is the API returning them?)
  → frontend api/products.js listProducts()
  → stores (none) → StorefrontView.vue watch on route query
  → ProductGrid.vue → ProductCard.vue
```
Quick checks: is `active` false? Is `visibility` filtering them out? Did the store fail to load
(`ProductGrid` shows an `AppAlert` on error)? Note the list returns **26 of 27** — `CHG-001` is
seeded inactive.

### Login doesn't work

```
1. POST /api/auth/login directly with curl  ← START HERE, removes the browser from the picture
     correct credentials → 200 + JWT?
2. PostgreSQL users table → does the email exist?  left(password_hash,7) = '$2a$10$'?
3. password → AuthService.authenticate() → passwordEncoder.matches()
4. wrong identifier shape → LoginRequest @Email → 422 "must be a well-formed email address"
5. JWT generated? → JwtTokenProvider.generateToken()   (decode header/payload)
6. JwtAuthenticationFilter → extracts Bearer, loads user, grants ROLE_<role>
7. CORS → OPTIONS preflight must be 200          ← this was the real failure; see Appendix C
8. frontend api/client.js → baseURL from VITE_API_BASE_URL, Authorization interceptor
9. stores/auth.js login() → stores token + user in localStorage
10. router/index.js beforeEach → requiresAuth / requiresAdmin guards
11. console/network tab → is the preflight failing? is a 422 being shown as a generic error?
```

### Images don't appear

```
MongoDB products.image_url  →  is it null?            ← THIS was the bug; see Appendix B
  → ProductDocument @Field("image_url")
  → GET /api/products → is image_url non-null in the JSON?
  → frontend utils/image.js resolveImageUrl()
  → ProductThumb.vue: imageUrl / @error / placeholder
  → <img src> — fetch that exact URL by hand
       /products/<SKU>.svg  → must return 200 image/svg+xml from :5173
       /uploads/products/…  → must return 200 from :8000 (WebConfig /uploads/** handler)
  → static resource mapping: WebConfig.addResourceHandlers()
  → filesystem: docker compose exec backend ls -la /app/uploads/products
  → Docker volume: `uploads_data:/app/uploads` on backend AND java-worker
```
Gotcha: a missing `/products/<SKU>.svg` gets the SPA fallback (`200 text/html`), so `<img>`
fails and silently shows the placeholder.

### Search doesn't work

```
Elasticsearch:  curl localhost:9200/orders/_count        (expect 48)
  → index exists?  OrderSearchRepository.ensureIndex()
  → mapping: nested items? search_as_you_type customer/title?
  → SearchService.buildQuery() / buildOmniQuery() / termQuery()
       • AND across terms (OR makes "Wendy Wireless" match everything)
       • range clauses need the typed builder, not a literal {"range":…} string
       • date_to must be +1 day for inclusivity
  → OrderSearchRepository.search() (aggregations, trackTotalHits)
  → POST /api/search/orders as ADMIN (401/403 = auth, 400 = bad query, 503 = ES down)
  → frontend api/search.js → AdminSearchView.vue (350 ms debounce)
```

### Order doesn't appear in Elasticsearch

```
PostgreSQL orders → does the row exist?  search_indexed_at set?
  → RabbitMQ  →  queue orders_sync non-empty? (mgmt UI :15672)
       publish failed? SyncPublisher returns {enqueued:false, error} in the response
  → OrderSyncListener → is the consumer connected? (backend AND java-worker both consume)
       manual ack + 5 retries, no requeue on final failure → a poison message is DROPPED
  → OrderSearchRepository.indexOrder() → _id = order_id (idempotent overwrite)
  → freshness: app.elasticsearch.refresh-on-write=true
  → order ids advance on every seed → stale docs cause a transient mismatch;
    the seeder guards this with recreateIndex() first
```

### Login works but a page is blank / redirects

```
browser localStorage  → meridian.auth.token / meridian.auth.user present?
  → stores/auth.js fetchMe()  (clears the session if it fails)
  → router beforeEach:
       requiresAuth && !isAuthenticated  → /login?redirect=…
       requiresAdmin && !isAdmin          → /
  → isAdmin derives from user.role (NOT from decoding the JWT)
  → network tab: which request 401'd/403'd/500'd?
```

### Nothing works at all

```
docker compose ps                 → all 7 containers healthy?
curl localhost:8000/api/health    → which dependency is "down"?  (503 = degraded)
docker compose logs backend       → look for ERROR + "Unhandled error:"
mvn -q compile                    → build actually succeeded?
```

---

## Appendix B — Image issue diagnosis

**Reported:** *"Images for each product are not visible."*
Diagnosed independently of the earlier seed problems; no code was changed until the root cause
was proven.

### STEP 1 — Actual MongoDB product documents

Inspected `WM-001` and enumerated every top-level key across the collection:

```
{_id, sku, title, description, price, category, tags[], attributes{},
 variants[], active, created_at, updated_at, _class}
```

Existence counts for image-ish fields:

```
image: 0   image_url: 0   imageUrl: 0   images: 0   thumbnail: 0   photo: 0   picture: 0
```

→ **MongoDB has no image field at all**, even though `ProductDocument` declares
`@Field("image_url") private String imageUrl;`.

### STEP 2 — What `GET /api/products` actually returns

```json
{ "sku": "ORG-001", "title": "Desk Organizer Bamboo", …, "image_url": null, … }
```

`image_url` key **present** but `null` — **26 of 26 items null**.
→ The DTO mapping and Jackson serialisation are fine. The *data* is missing.

### STEP 3 — How the frontend builds the `<img src>`

`ProductThumb.vue`: `resolveImageUrl(props.product?.image_url)`.
`utils/image.js`: empty/null → `null` → `showImage = false` → **branded placeholder**.
→ Every card rendered the placeholder. That is exactly what "images not visible" looks like.

### STEP 4 — Static resource configuration

* `WebConfig.addResourceHandlers()` maps `/uploads/**` → `file:${app.uploads.dir}/` — present
  and correct.
* `app.uploads.dir` = `/app/uploads`; `uploads_data` volume is mounted; the directory exists
  but is **empty**.
* `/uploads/**` → `500` for a non-existent file (the catch-all handler; no file has ever been
  uploaded).

### STEP 5 — Are representative image URLs reachable?

```
http://localhost:5173/products/WM-001.svg   → 200 image/svg+xml 1578B   ✅
http://localhost:5173/products/default.svg  → 200 image/svg+xml 1422B   ✅
http://localhost:5173/products/NOPE-999.svg → 200 text/html   (SPA fallback!)
```
→ The files exist and are served correctly.

### STEP 6 — Do image files exist in the backend container?

`docker compose exec backend ls -la /app/uploads/products` → **empty**.
Correct for this design: the seeded artwork lives in the *frontend* bundle
(`frontend/public/products/`, **26** `<SKU>.svg` files), not in the backend.

SKU↔artwork coverage: **26 of 27** SKUs have a drawing; only `CHG-001` does not.

### STEP 7 — ROOT CAUSE

**Category A + E — the seed never populated `image_url`.**

Confirmed by `grep -n "image" SeedRunner.java` → **no match at all**. A stray out-of-band script
`update_all_images.py` at the repo root contained the intended `SKU → /products/<SKU>.svg`
mapping, but it was never wired into the seed, so any re-seed wiped it.

Ruled out by evidence: API layer ✅ (returns the field), frontend URL construction ✅
(`resolveImageUrl` handles this exact shape), static resource mapping ✅ (`/uploads/**`
configured), Docker volume ✅ (mounted), file existence ✅ (26 SVGs, all `200`), port/origin ✅
(relative URL against the app origin).

### Smallest fix applied

Only `SeedRunner.seedProducts()`:

```java
String sku = (String) p.get("sku");
doc.setImageUrl(SKU_WITHOUT_ARTWORK.contains(sku)
    ? DEFAULT_ARTWORK_URL
    : "/products/" + sku + ".svg");
```

reusing the convention the project already established. No schema/API/frontend/architecture
change.

### Verification

| Check | Before | After |
|---|---|---|
| Mongo docs with `image_url` | **0 / 27** | **27 / 27** |
| `image_url` null | all | **0** |
| `GET /api/products` non-null | **0 / 26** | **26 / 26** |
| `WM-001` | — | `/products/WM-001.svg` |
| `CHG-001` | — | `/products/default.svg` |
| URL fetches | already 200 | **200 `image/svg+xml`** (1578 / 3087 / 1640 / 1422 B) |

Seed log: `Products seeded: 27` … `Seed data initialization completed!`

**Not verified:** a rendered screenshot — no desktop browser is attached to this session.

---

## Appendix C — Admin login issue diagnosis

**Reported:** *"I cannot log into the admin portal."* Traced as `admin`/`admin` through the
whole system, without assuming it related to the old seed failure.

### STEP 1 — PostgreSQL user

**The table has no `username` column.** `\d users` → `id, name, email, password_hash, role,
created_at` (unique index on `email`, check constraint limiting `role` to `CUSTOMER|ADMIN`).

```
id=468  name=Admin  email=admin@example.com  role=ADMIN  hash=$2a$10$…  len=60
```

### STEP 2 — Is the hash BCrypt?

Yes — prefix `$2a$10$`, length **60**.

### STEP 3 — Direct call to the login endpoint (no browser)

| Body | HTTP | Response |
|---|---|---|
| `{"email":"admin@example.com","password":"admin"}` | **200** | JWT issued, `user.role = "ADMIN"` ✅ |
| `{"email":"admin","password":"admin"}` | **422** | `email must be a well-formed email address` |
| `{"username":"admin","password":"admin"}` | **422** | `email must not be blank` |

→ **The backend login API works perfectly.** `admin` is simply not a valid identity; the field
is `email`.

### STEP 4 — Does the login POST trigger a browser preflight? **Yes.**

`Content-Type: application/json` is not a CORS-safelisted content type, so the browser must send
an `OPTIONS` preflight first.

```
OPTIONS /api/auth/login
  Origin: http://localhost:5173
  Access-Control-Request-Method: POST
  Access-Control-Request-Headers: content-type
→ HTTP/1.1 403        body: "Invalid CORS request"
```

**Before the fix**, every preflight returned 403 regardless of headers, while simple `GET`s
returned 200. **The browser therefore never sent the actual POST** — login failed even with
correct credentials.

Note the subtlety: the *real* POST (when forced with curl) returned **200 with
`Access-Control-Allow-Origin: http://localhost:5173`** — so the request handler itself was
CORS-correct; only the preflight failed.

### STEP 5 — The actual frontend login request

| Aspect | Value |
|---|---|
| URL | `VITE_API_BASE_URL` → `http://localhost:8000` + `/api/auth/login` |
| Method | `POST` |
| Headers | `Content-Type: application/json` → **triggers preflight**; `Authorization` added only when a token already exists |
| Body | `{ email, password }` — the field is **Email**, placeholder `you@example.com` |
| Credentials | not used (`withCredentials` unset) |
| Base URL | baked-in absolute `http://localhost:8000` → cross-origin from `:5173` |

### STEP 6 — `JwtAuthenticationFilter`

Extracts `Authorization: Bearer …` → `validateToken` → `getUserIdFromToken` →
`userRepository.findById` → grants **`ROLE_` + role** → sets the `SecurityContextHolder`.
Verified working: the token minted in STEP 3 successfully authenticated
`GET /api/auth/me` (200) and `POST /api/search/orders` (200).

### STEP 7 — Admin route protection in Vue

`/admin` carries `meta.requiresAuth + requiresAdmin`. `beforeEach`:
`requiresAuth && !isAuthenticated` → `/login?redirect=…`;
`requiresAdmin && !isAdmin` → `/storefront`. `isAdmin` reads `user.role` from
`localStorage`. This layer was never reached, because the request never left the browser.

### STEP 8 — Does the backend accept the JWT? Yes.

```
GET  /api/auth/me          + admin JWT  → 200 {"id":477,…,"role":"ADMIN"}
POST /api/search/orders    + admin JWT  → 200 results
GET  /api/orders/…         no token     → 401
POST /api/search/orders    no token     → 401
POST /api/search/orders    customer JWT → 403
GET  /api/orders/<other's> customer JWT → 403
```

### STEP 9 — The exact failure layer

**The CORS preflight.** Not the database, not BCrypt, not the login API, not JWT generation,
not the filter, not authorities, not the router.

#### Why the preflight failed — root cause proven by bytecode

`SecurityConfig` declares `private final CorsConfigurationSource corsConfigurationSource;` —
but `WebConfig` only exposed a `@Bean CorsFilter`, and **`CorsFilter` does not implement
`CorsConfigurationSource`** (verified by `javap`). Spring Boot 3.2.5 auto-configuration defines
**no** `CorsConfigurationSource` bean either (verified by inspecting the jar).

So Spring resolved the dependency **by type** to the only remaining candidate:
Spring MVC's `HandlerMappingIntrospector`, which *does* implement `CorsConfigurationSource`
(bean `mvcHandlerMappingIntrospector`). It returns **`null`** here, because no handler declares
its own CORS rules (`aconst_null; areturn` in `getCorsConfiguration`).

`DefaultCorsProcessor.processRequest` (Spring 6.1.6) with a `null` configuration —
disassembled:

```
114: aload_1            // config
115: ifnonnull 139      // if (config != null) → handle properly
118: iload 5            // isPreflight
120: ifeq 137
123: rejectRequest()    // ← null config + PREFLIGHT  → 403
135: iconst_0; ireturn
137: iconst_1; ireturn  // ← null config + SIMPLE req → allow
```

| Request type | `config` | Result |
|---|---|---|
| Simple `GET` with `Origin` | `null` | **allowed** → passes through (and picks up ACAO from the separate container `CorsFilter` bean) — *which is why simple GETs looked healthy* |
| **Preflight `OPTIONS`** | `null` | **`rejectRequest()` → 403 `Invalid CORS request`** |

That asymmetry is precisely the reported symptom, and it blocked every browser `POST` carrying
`Content-Type: application/json` — login included.

#### Smallest fix applied

`WebConfig` — expose the configuration that was already written as a proper bean:

```java
@Bean
@Primary
public CorsConfigurationSource corsConfigurationSource() { /* /** → allowed origins */ }

@Bean
public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
    return new CorsFilter(corsConfigurationSource);
}
```

`@Primary` makes it win over `HandlerMappingIntrospector`; `corsFilter` now reuses the same
source so the two cannot drift. **`SecurityConfig` was not modified.**

#### Verification

| Check | Before | After |
|---|---|---|
| `OPTIONS /api/auth/login` preflight | **403** | **200** + `Access-Control-Allow-Origin: http://localhost:5173`, `Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS`, `Allow-Headers: content-type`, `Allow-Credentials: true`, `Max-Age: 3600` |
| `OPTIONS /api/search/orders` preflight | 403 | **200** |
| Simple `GET /api/health` with `Origin` | 200 | **200** + ACAO + **body intact (full JSON payload)** — no regression |
| Preflight from `http://evil.test` | 403 | **403** (origin allow-list still enforced) |
| Login `admin@example.com`/`admin` | 200 (curl only) | **200**, `role: ADMIN` |
| Admin JWT → `/api/auth/me` | — | **200** |
| Admin JWT → `/api/search/orders` | — | **200** |
| No token → `/api/orders/…` | — | **401** |
| Customer JWT → `/api/search/orders` | — | **403** |
| Customer JWT → another user's order | — | **403** |
| Frontend test suite | **25 passed / 3 failed** | **28 passed / 0 failed** |

#### Conclusion

Two independent causes, both real:

1. **Blocking root cause:** the CORS preflight returned 403, so the browser never issued the
   login request. *(Fixed.)*
2. **Credential form:** `admin`/`admin` is not a valid pair — the identity is an **email**. The
   correct pair is **`admin@example.com` / `admin`**, which is exactly what the login form's own
   hint displays. *(Not a code defect.)*

---

*See also:* [`SYSTEM_ARCHITECTURE.md`](SYSTEM_ARCHITECTURE.md) for the architecture and caveats,
[`FILE_BY_FILE_GUIDE.md`](FILE_BY_FILE_GUIDE.md) for the source-level tour.
