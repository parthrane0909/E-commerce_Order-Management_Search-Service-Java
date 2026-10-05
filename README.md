# Meridian — E-Commerce Order Management & Search Service

A full-stack **polyglot persistence** demonstration: a Vue 3 storefront and admin console backed by
a Java / Spring Boot API, with each data store used strictly for the job it is best at.

```bash
cp .env.example .env
docker compose up -d --build
docker compose --profile tools run --rm seed   # load demo data
```

| | |
|---|---|
| API | <http://localhost:8000/api/health> |
| Storefront | <http://localhost:5173> |
| RabbitMQ management | <http://localhost:15672> (`ecommerce` / `change_me_rabbit`) |

---

## 1. Project overview

Meridian models the part of an online shop that customers and staff both touch:

* **Customers** browse the catalog, search and filter products, maintain a cart, and check out.
  Prices are re-fetched live from the server before an order is placed.
* **Admins** get an Elasticsearch-backed order search dashboard with KPI cards (revenue, counts by
  status), status/date/price filters, phrase and as-you-type search, and a catalog manager for
  creating, editing, deactivating products and uploading images.

Each fact has exactly one home: PostgreSQL owns orders and users, MongoDB owns the product
catalog, Elasticsearch owns no truth at all — it is a disposable projection rebuilt from
PostgreSQL.

---

## 2. Architecture

```
                         Browser  (http://localhost:5173)
                                    │
                                    ▼
                    ┌───────────────────────────────────┐
                    │  FRONTEND — Vue 3 + Vite :5173    │
                    │  Router · Pinia · Axios           │
                    └───────────────┬───────────────────┘
                                    │  /api/**   (CORS preflight + Bearer JWT)
                                    ▼
                    ┌───────────────────────────────────┐
                    │  BACKEND — Spring Boot :8000      │
                    │  Controllers → Services → Repos   │
                    └───┬───────────┬───────────┬───────┘
                        │           │           │
        ┌───────────────▼──┐  ┌─────▼─────┐  ┌──▼───────────────┐
        │   PostgreSQL 16  │  │ MongoDB 7 │  │ Elasticsearch 8  │
        │   users, orders, │  │ products  │  │ index: orders    │
        │   order_items    │  │ (catalog) │  │ (projection)     │
        └───────▲──────────┘  └───────────┘  └────────▲─────────┘
                │                                     │
                │  source of truth                    │  indexed by
                │                                     │
                └──────┐              ┌───────────────┘
                       │              │
                       ▼              │
                ┌─────────────────────────────────────┐
                │            RabbitMQ 3.13            │
                │   exchange orders.sync.exchange     │
                │   queue     orders_sync             │
                └────────────────┬────────────────────┘
                                 │  consume
                                 ▼
                ┌─────────────────────────────────────┐
                │   JAVA LISTENER (Spring AMQP)       │
                │   OrderSyncListener @RabbitListener │
                │   runs in backend + java-worker     │
                └─────────────────────────────────────┘
```

---

## 3. Technology stack

| Layer | Technologies |
|---|---|
| **Frontend** | Vue 3, Vite, Pinia, Vue Router, Axios, Vitest |
| **Backend** | Java 21, Spring Boot 3.2.5, Maven, Spring Security, Spring Data JPA / Hibernate, Spring AMQP, Spring Data MongoDB |
| **Databases & services** | PostgreSQL 16, MongoDB 7, Elasticsearch 8.15.3, RabbitMQ 3.13 |
| **Infrastructure** | Docker, Docker Compose, Nginx (production frontend) |

---

## 4. Database responsibilities

| Store | Owns | Why |
|---|---|---|
| **PostgreSQL** | `users`, `orders`, `order_items` | ACID transactions, foreign keys, permanent audit records. Source of truth for every order. |
| **MongoDB** | `products` (catalog) | Products are nested documents — `tags[]`, `attributes{}`, `variants[]` — always read together. No JOINs, and per-category attributes need no schema migration. |
| **Elasticsearch** | `orders` search index | Filtering and aggregating across thousands of orders in milliseconds. Disposable — rebuildable from PostgreSQL at any time. |
| **RabbitMQ** | `orders_sync` queue | Decouples *placing an order* (must be fast and durable) from *updating search* (may lag). |
| **Filesystem** | `/app/uploads/products` | Admin-uploaded product images, served at `/uploads/**`. Seeded catalog artwork ships in the frontend bundle instead. |

### Order snapshot semantics

`order_items` stores a **frozen copy** of `title` and `unit_price` at purchase time, plus the
Mongo `_id` of the product. Repricing or renaming a product in the catalog can never rewrite what
a customer actually paid, and deactivating a product never orphans a historical order.

---

## 5. Authentication

* **BCrypt** password hashes (`$2a$10$…`) stored in PostgreSQL.
* **JWT (stateless)** issued by `POST /api/auth/login`. The token carries `sub` (user id), `iat`
  and `exp` — **no role claim**. The role is loaded from PostgreSQL on every request by
  `JwtAuthenticationFilter`, which grants `ROLE_CUSTOMER` or `ROLE_ADMIN`.
* Tokens are stored in `localStorage` and sent as `Authorization: Bearer <token>`.
* Login identity is an **email address**, e.g. `admin@example.com` — the API requires `@Email`.
* Enforcement happens twice: URL rules in `SecurityConfig`, plus method-level
  `@PreAuthorize("hasRole('ADMIN')")` on catalog writes, image upload, order status changes and
  search. Customers may only read their own orders (403 otherwise).
* CORS is configured by a single `@Primary CorsConfigurationSource` bean in `WebConfig`, so
  browser preflights from `http://localhost:5173` are accepted.

---

## 6. Order flow

```
Cart (client-side)
   │  POST /api/orders  { items: [ { product_id, quantity } ] }
   ▼
OrderService.createOrder()          @Transactional
   ├─ resolveCart()                  reads MongoDB — rejects missing/inactive/out-of-range prices
   ├─ insert Order                   provisional TMP-<uuid> number, then ORD-%06d after the id is known
   ├─ insert OrderItems               title + unit_price snapshots, cascade persist
   └─ commit
   │
   └─ finally: SyncPublisher.publishOrderSync(id, "order_created")   ← after commit
         │
         ▼
      RabbitMQ  →  queue orders_sync
         │
         ▼
      OrderSyncListener @RabbitListener
         ├─ load order (one JOIN FETCH — items + user)
         ├─ ensureIndex() → indexOrder() into Elasticsearch (_id = order_id)
         └─ stamp orders.search_indexed_at in PostgreSQL
```

If the broker is unreachable the order is **still written** — the response carries
`sync: { enqueued: false, error }` rather than failing checkout.

---

## 7. Elasticsearch synchronization

The current synchronization architecture is:

```
PostgreSQL
    ↓
RabbitMQ
    ↓
Java RabbitMQ listener / worker   (Spring AMQP @RabbitListener)
    ↓
Elasticsearch
```

There is **no Celery** in this project — indexing is performed by
`OrderSyncListener`, a Spring AMQP `@RabbitListener` on the durable `orders_sync` queue.

* **Topology:** topic exchange `orders.sync.exchange` → queue `orders_sync`, routing key
  `orders.sync`. Messages are `{ orderId, reason }` where reason is `order_created` or
  `status_update`.
* **Produced:** after a successful commit of `POST /api/orders`, and after
  `PATCH /api/orders/{id}/status`.
* **Consumed:** by `backend` **and** `java-worker` — both run the same jar, so they act as
  competing consumers. Indexing runs off the request thread and scales independently of HTTP.
* **Retry policy** (`application.yml` → `spring.rabbitmq.listener.simple.retry`): manual ack,
  prefetch 1, concurrency 2, 5 attempts, 10 s initial interval doubling to a 120 s max, and
  `default-requeue-rejected: false` so a poison message never loops forever.
* **Idempotent:** the document is written with `_id = order_id`, so replaying a message is safe.
* **Rebuild:** delete the index and re-run the seeder — `recreateIndex()` runs before bulk
  indexing, because order ids advance on every seed run.

---

## 8. Docker setup

Ten services in `docker-compose.yml`:

| Service | Image / build | Ports | Purpose |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` | 5432 | orders, users, order_items |
| `mongodb` | `mongo:7` | 27017 | product catalog |
| `elasticsearch` | `elasticsearch:8.15.3` | 9200 | search index |
| `rabbitmq` | `rabbitmq:3.13-management-alpine` | 5672, 15672 | sync queue |
| `backend` | `./backend` | 8000 | the HTTP API |
| `java-worker` | `./backend` | — | second instance — competing RabbitMQ consumer |
| `frontend` | `./frontend` (dev) | 5173 | Vite dev server |
| `frontend-prod` | `./frontend` (prod) | 8080 | nginx (`--profile prod`) |
| `seed` | `./backend` | — | one-shot seeder (`--profile tools`) |
| `reindex` | `./backend` | — | reindex helper (`--profile tools`) |

Volumes: `postgres_data`, `mongodb_data`, `elasticsearch_data`, `rabbitmq_data`,
`frontend_node_modules`, `uploads_data` (mounted at `/app/uploads` on `backend` and `java-worker`).

Inside the Docker network use **service names** (`postgres`, `mongodb`, `elasticsearch`,
`rabbitmq`, `backend`) — `localhost` never resolves to a sibling container. From the browser use
`localhost`.

---

## 9. Environment configuration

Copy the template, then adjust:

```bash
cp .env.example .env
```

| Variable | Default | Notes |
|---|---|---|
| `API_PORT` | `8000` | backend HTTP port |
| `POSTGRES_*` | `ecommerce` / `change_me_postgres` | DB user, password, database |
| `MONGO_URI` | `mongodb://localhost:27017` | overridden to `mongodb://mongodb:27017` in-network |
| `ELASTICSEARCH_URL` | `http://localhost:9200` | overridden to `http://elasticsearch:9200` in-network |
| `RABBITMQ_DEFAULT_USER` / `_PASSWORD` | `ecommerce` / `change_me_rabbit` | broker credentials |
| `RABBITMQ_SYNC_QUEUE` | `orders_sync` | publish/consume queue name |
| `ELASTICSEARCH_REFRESH_ON_WRITE` | `true` | index refreshes immediately after writing |
| `JWT_SECRET_KEY` | dev placeholder | **set a long random value outside local dev** |
| `JWT_ACCESS_TOKEN_EXPIRE_MINUTES` | `1440` | token lifetime (24 h) |
| `CORS_ORIGINS` | `http://localhost:5173` | comma-separated browser origins |
| `UPLOADS_DIR` | `/app/uploads` | product image storage |
| `VITE_API_BASE_URL` | `http://localhost:8000` | absolute API base used by the browser |
| `VITE_PROXY_TARGET` | `http://backend:8000` | Vite dev proxy target |
| `WEB_PORT` | `8080` | nginx frontend (`--profile prod`) |

`.env` is gitignored — never commit real secrets.

---

## 10. Starting the application

```bash
cp .env.example .env
docker compose up -d --build
docker compose --profile tools run --rm seed    # see "Seeding" below
```

Infrastructure comes up first (`condition: service_healthy`), then `backend`, `java-worker` and
`frontend`.

### Seeding data

```bash
docker compose build seed
docker compose --profile tools run --rm seed
```

Seeds 9 users, 27 products (26 active), 48 orders / 107 order items, applies the price-rename
snapshot demo, and rebuilds the Elasticsearch index.

> **The seeder does not exit** — it boots the same Spring Boot jar, Tomcat stays alive and the
> command times out. That is expected: judge success by the log line
> `Seed data initialization completed!`, not by the exit code.

### Product catalog seed (3,000 products)

```bash
docker compose build seed
docker compose --profile tools run --rm catalog-seed          # top up to the target
CATALOG_RESET=true docker compose --profile tools run --rm catalog-seed   # optional rebuild
```

Separate from the demo seeder and gated behind the `catalog` profile, so it never runs on a
normal application start. It generates deterministic products (`RANDOM_SEED = 20261005`) on top
of the 27 demo rows — the final catalog is demo + generated, ≈ 3,027 products across 31 categories.

- **Idempotent** — a second run inserts nothing (`nothing to insert`).
- **Never overwrites** — every existing SKU and title is reserved, so demo products are stepped
  around rather than replaced; generated SKUs use the `ABC-1234` namespace, demo SKUs `ABC-123`.
- **Validated artwork** — `backend/src/main/resources/catalog/image-pool.json` lists the 51 shared
  SVGs in `frontend/public/products/`; an unmapped category aborts the run instead of writing a
  broken `image_url`. No per-product image files are created.
- **Exits 0/1** by design (`--rm` leaves no container behind). `CATALOG_RESET=true` deletes only
  the generated slice first; the demo products and all orders are untouched.
- The run ends with a verification block: totals, active/inactive, category list, SKU/title
  uniqueness, `image_url` coverage and distinct artwork in use.

`CATALOG_PRODUCT_COUNT` (default `3000`) sets the target.

### Running the backend without Docker

```bash
cd backend
./mvnw clean package -DskipTests
java -jar target/*.jar
```

Requires PostgreSQL, MongoDB, Elasticsearch and RabbitMQ reachable on the host ports from
`.env`.

### Running the frontend without Docker

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

With `VITE_API_BASE_URL` empty the app uses relative `/api` requests through the Vite proxy.

### Production frontend

```bash
docker compose --profile prod up -d --build frontend-prod   # http://localhost:8080
```

---

## 11. Demo credentials

| Role | Email | Password |
|---|---|---|
| **Admin** | `admin@example.com` | `admin` |
| Customer | `john.doe@example.com` | `john` |
| Customer | `jane.smith@example.com` | `jane` |
| Customer | `wendy.wireless@example.com` | `wendy` |
| Customer | `alex.rivera@example.com` | `alex` |
| Customer | `sam.patel@example.com` | `sam` |
| Customer | `casey.nguyen@example.com` | `casey` |
| Customer | `morgan.lee@example.com` | `morgan` |
| Customer | `riley.brooks@example.com` | `riley` |

The identity field is an **email** — `admin` alone is rejected by validation.

---

## 12. Main API groups

Base URL `http://localhost:8000`. Auth column: **public** = no token, **authenticated** = any valid
JWT, **ADMIN** = `ROLE_ADMIN`.

| Method | Path | Purpose | Auth |
|---|---|---|---|
| POST | `/api/auth/login` | verify credentials, issue JWT | public |
| POST | `/api/auth/logout` | acknowledge logout (client-side) | public |
| GET | `/api/auth/me` | current user from the token | public |
| GET | `/api/products` | list / filter / sort catalog (`cursor` or `page`) | public |
| GET | `/api/products/facets` | category + tag facet counts | public |
| GET | `/api/products/{id}` | single product | public |
| POST | `/api/products` | create product | ADMIN |
| PATCH | `/api/products/{id}` | partial update | ADMIN |
| DELETE | `/api/products/{id}` | soft delete (`active=false`) | ADMIN |
| POST | `/api/products/{id}/image` | upload image (multipart, ≤ 5 MB) | ADMIN |
| DELETE | `/api/products/{id}/image` | remove image | ADMIN |
| POST | `/api/orders` | checkout | authenticated |
| GET | `/api/orders/{id}` | order detail (ownership enforced) | authenticated |
| PATCH | `/api/orders/{id}/status` | `PENDING` / `PROCESSING` / `SHIPPED` | ADMIN |
| POST | `/api/search/orders` | order search, filters, KPI aggregations | ADMIN |
| GET | `/api/health` | dependency health + latency | public |

Actuator exposes `/actuator/health` and `/actuator/info` (authenticated).

API request/response keys are **snake_case** (`order_number`, `min_price`, `date_from`, …).

---

## 13. Testing and verification

```bash
# Java build + unit tests (cursor, catalog generator, image pool)
cd backend && mvn clean package

# Frontend build and tests
cd frontend && npm run build
cd frontend && npm test -- --run

# End-to-end storefront checks (needs the stack running on :5173 / :8000)
cd frontend && npm run test:e2e

# Stack
docker compose up --build -d
docker compose ps

# API health — all four dependencies should report "up"
curl http://localhost:8000/api/health

# Catalog (MongoDB-backed)
curl -s 'http://localhost:8000/api/products?limit=5'

# Cursor walk — has_more flips to false and next_cursor to null at the end
curl -s 'http://localhost:8000/api/products?limit=100' | python3 -c 'import sys,json;d=json.load(sys.stdin);print(len(d["items"]), d["total"], d["has_more"], bool(d["next_cursor"]))'

# Login and use the token
TOKEN=$(curl -s -X POST http://localhost:8000/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"admin"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

curl -s http://localhost:8000/api/auth/me -H "Authorization: Bearer $TOKEN"

# Admin search (Elasticsearch-backed)
curl -s -X POST http://localhost:8000/api/search/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"wireless","page":1,"limit":5}'

# Direct store checks
curl -s http://localhost:9200/orders/_count
curl -s http://localhost:8000/api/health | python3 -m json.tool
```

> The backend is a Spring Boot application and does **not** ship a Swagger UI — the table in
> section 12 is the complete API surface.

---

## 14. Cursor pagination

The storefront loads products a batch at a time and keeps appending as you scroll. `GET
/api/products` supports **both** styles: `page` for existing/legacy clients, `cursor` for the
storefront. Responses always keep the original shape (`items`, `page`, `limit`, `total`, `pages`)
and add two fields:

| Field | Meaning |
|---|---|
| `next_cursor` | opaque Base64URL continuation token, or `null` on the last batch |
| `has_more` | `true` only when a usable `next_cursor` was produced |

```bash
curl -s 'http://localhost:8000/api/products?limit=50' | python3 -c 'import sys,json;d=json.load(sys.stdin);print(len(d["items"]), d["total"], d["has_more"], (d["next_cursor"] or "")[:24])'

# follow it — no duplicates, ordering unchanged, `total`/`pages`/`limit` unaffected
curl -s "http://localhost:8000/api/products?limit=50&cursor=$CURSOR"
```

Rules the endpoint guarantees:

- The query is a single MongoDB `find` with **sort + cursor predicate + `limit+1`** — it never
  fetches the whole result set and slices it in Java.
- The cursor encodes the sort position *and* a fingerprint of the query
  (`sort`, `visibility`, `category`, `tags`, `q`). Reusing it under a different query — or sending
  a malformed/tampered one — returns **422 `invalid_cursor`**, never a 500.
- It composes with every existing filter: `q`, `category`, `tags`, `sort`, `visibility`, `limit`.
- An empty `cursor=` is treated as "no cursor" and returns the first batch.

**Frontend behaviour** (`frontend/src/views/StorefrontView.vue`): an `IntersectionObserver` on a
sentinel element with `rootMargin: '600px 0px'` requests the next batch; batches are *appended*
with de-duplication by product `id`, never replaced. Changing `q`, `category` or `sort` discards
the list **and** the cursor, and any in-flight append is dropped by a request-generation guard so a
stale response can never land on screen. The only UI added is `Loading more products…` while a
batch is in flight and `You've reached the end.` at the terminus — there is no page-number control
on the storefront, and a legacy `?page=` link is stripped from the URL on load.

---

## Project layout

```
backend/                 Java / Spring Boot API and RabbitMQ listener
├── Dockerfile           multi-stage Maven build → JRE runtime
├── pom.xml              Spring Boot 3.2.5, Java 21
└── src/main/java/com/meridian/backend/
    ├── auth/            login, logout, current user
    ├── config/          security, CORS, Mongo, RabbitMQ, Elasticsearch
    ├── images/          product image upload / delete
    ├── messaging/       SyncPublisher, OrderSyncListener
    ├── orders/          controllers, service, JPA entities + repositories
    ├── products/        controllers, service, Mongo document + repository
    ├── search/          admin search controller, service, ES client
    ├── security/        JWT provider, authentication filter, 401/403 handlers
    ├── products/seed/   deterministic 3,000-product generator + image pool (catalog profile)
    ├── resources/catalog/image-pool.json  shared-artwork manifest
    └── SeedRunner.java  demo data + snapshot demo + ES reindex

frontend/                Vue 3 application
├── Dockerfile           dev / prod (nginx) stages
├── playwright.config.js end-to-end runner (npm run test:e2e)
├── e2e/                 storefront infinite-scroll checks
├── tests/               Vitest unit tests
└── src/
    ├── api/             axios client + one module per resource
    ├── router/          routes and auth guards
    ├── stores/          auth, cart, toast (Pinia)
    ├── views/           storefront, checkout, login, admin pages
    ├── components/      common, storefront, checkout, auth, admin
    └── utils/           image, currency, dates, status, validation, debounce

docker-compose.yml       all eleven services
```
