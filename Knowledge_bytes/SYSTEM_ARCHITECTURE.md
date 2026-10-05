# Meridian — System Architecture

> **Part A of the system knowledge package.**
> Companion documents: [`SYSTEM_WORKFLOW.md`](SYSTEM_WORKFLOW.md) (how a request travels end to end)
> and [`FILE_BY_FILE_GUIDE.md`](FILE_BY_FILE_GUIDE.md) (what every source file does).

This document explains **what the system is, how it is shaped, and why**. It is written for a
developer who knows basic programming but has not seen this project before. Every diagram,
class name, endpoint and number below was taken from the actual source code and the running
system — nothing is invented.

---

## Table of contents

| § | Section |
|---|---|
| [1](#1-what-the-project-is) | What the project is |
| [2](#2-high-level-architecture) | High-level architecture + ASCII diagram |
| [3](#3-database-responsibility) | Database responsibility (MongoDB / PostgreSQL / Elasticsearch / RabbitMQ / worker) |
| [4](#4-order-snapshot-semantics) | Order snapshot semantics |
| [5](#5-search-architecture) | Search architecture |
| [6](#6-image-architecture) | Image architecture |
| [7](#7-docker-architecture) | Docker architecture |
| [8](#8-api-map) | API map (all 16 endpoints) |
| [9](#9-important-design-decisions) | Important design decisions |
| [10](#10-known-caveats) | Known caveats |

---

## 1. What the project is

Meridian is a **demonstration e-commerce order-management and search service**. It models the
part of an online shop that customers and staff both touch, split across four backing systems.

### What the customer does

1. Browses the storefront — product grid, category rail, tag marquee, text search, sort and
   pagination (`StorefrontView.vue`).
2. Adds products to a cart (`stores/cart.js`, opened from `CartDrawer.vue`).
3. Goes to checkout, where prices are **re-fetched live** from the server before placing the
   order (`CheckoutView.vue`).
4. Places the order — the backend prices it against MongoDB, writes it to PostgreSQL, and
   publishes a "sync" message to RabbitMQ.
5. Views their own order details, and can see whether it has reached the search index.

### What the admin does

1. Signs in at `/login` with an ADMIN account (`admin@example.com` / `admin`).
2. Lands on the **search dashboard** (`/admin/search`) — Elasticsearch-backed order search with
   KPI cards (revenue, order counts by status), status/date/price filters, phrase and
   as-you-type search, and pagination.
3. Opens a single order (`/admin/orders/:id`) — served from **PostgreSQL** as the source of
   truth — and changes its status (`PENDING` → `PROCESSING` → `SHIPPED`).
4. Manages the catalog (`/admin/catalog`): create, edit, deactivate products and upload product
   images.

### Why multiple databases?

A single relational database *could* store all of this. The project deliberately does not,
because the four jobs have genuinely different shapes:

| Job | Shape of the data | Why the obvious database fits |
|---|---|---|
| Product catalog | Deeply nested: `tags[]`, `attributes{}`, `variants[]` per product | MongoDB stores the whole product as one natural document — no JOINs for a nested array that is always read together |
| Orders | Rigid, relational, must be transactional and auditable | PostgreSQL gives ACID writes, foreign keys, and stable historical records |
| Search | Filter across thousands of orders in milliseconds, plus aggregations | Elasticsearch is purpose-built for inverted indexes and facets |
| Keeping search in sync | Search must never block placing an order | RabbitMQ lets the write finish first and sync later |

This pattern — **polyglot persistence** — is the point of the exercise: use each store for the
thing it is best at, and define the boundary between them precisely.

### What polyglot persistence solves here

* **Writes stay fast and correct.** Placing an order commits once in PostgreSQL. Indexing into
  Elasticsearch happens afterwards, out of band. A slow search cluster cannot make checkout
  fail.
* **Reads are shaped for the reader.** The storefront reads a *product* (one document). The
  admin dashboard reads *orders filtered and aggregated* (an index). The order detail page
  reads *one authoritative order row*. Each store answers in its own dialect.
* **Failure is isolated.** If Elasticsearch is down, browsing and checkout still work; only
  `/api/search/orders` reports `503 search_unavailable`. `/api/health` reports exactly which of
  the four dependencies is unhealthy.
* **Clear authority.** Each fact has exactly one home: PostgreSQL owns orders, MongoDB owns
  products, Elasticsearch owns *no* truth — it is a disposable projection.

> ⚠ **A note on what you are reading.** This repository also contains an earlier **Python
> FastAPI** implementation (`backend/`, 51 files) that `docker-compose.yml` does **not** build,
> and the top-level `README.md` still documents *that* one (Swagger at `:8000/docs`,
> `python -m pytest`). The service described throughout this document is the **Java** one —
> `backend-java/`. See [`FILE_BY_FILE_GUIDE.md` §0](FILE_BY_FILE_GUIDE.md#0-repository-root--what-is-actually-here).

---

## 2. High-level architecture

### The actual diagram

```
                        ┌──────────────────────────────────────────────┐
                        │   BROWSER  (origin http://localhost:5173)    │
                        └──────────────────────────────────────────────┘
                                          │
                 HTTP/JSON  (CORS preflight + request, Bearer JWT)
                                          │
        ┌─────────────────────────────────▼──────────────────────────────────┐
        │                    FRONTEND  —  frontend service                   │
        │  Vue 3 + Vite dev server :5173   (prod variant: nginx :8080)       │
        │  Router · Pinia (auth/cart/toast) · axios client                    │
        │  Serves its OWN bundled artwork:  /products/<SKU>.svg               │
        └─────────────────────────────────┬──────────────────────────────────┘
                                          │  /api/**  and  /uploads/**
                                          ▼
        ┌───────────────────────────────────────────────────────────────────┐
        │              BACKEND  —  Spring Boot 3.2.5 / Java 21  :8000       │
        │  com.meridian.backend                                            │
        │  ┌────────────────────────────────────────────────────────────┐   │
        │  │ Security filter chain:                                     │   │
        │  │   CorsFilter → JwtAuthenticationFilter → Authorization    │   │
        │  └────────────────────────────────────────────────────────────┘   │
        │  Controllers → Services → Repositories                            │
        │                                                                   │
        │  writes /uploads/<file>  ────────►  /app/uploads  (volume)        │
        └───┬──────────────┬───────────────┬───────────────────┬───────────┘
            │              │               │                   │
   (1) JDBC │   (2) Mongo  │  (3) AMQP     │ (4) REST client    │ (5) REST client
            │              │               │                   │
            ▼              ▼               ▼                   ▼
   ┌────────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌──────────────────┐
   │  PostgreSQL 16 │ │  MongoDB 7   │ │    RabbitMQ 3.13 │ │ Elasticsearch 8  │
   │  :5432         │ │  :27017      │ │    :5672/:15672  │ │    :9200         │
   │                │ │              │ │                  │ │                  │
   │  users         │ │  products    │ │ exchange         │ │  index: orders   │
   │  orders        │ │  (catalog)   │ │  orders.sync.    │ │  (nested items,  │
   │  order_items   │ │              │ │  exchange        │ │   customer,      │
   │                │ │              │ │ queue            │ │   aggregations)  │
   │  SOURCE OF     │ │  CATALOG     │ │  orders_sync     │ │                  │
   │  TRUTH for     │ │  AUTHORITY   │ │                  │ │  DISPOSABLE      │
   │  orders+users  │ │  for product │ │  messages:       │ │  PROJECTION      │
   │                │ │              │ │  {order_id,      │ │  rebuilt at any  │
   │                │ │              │ │   reason}        │ │  time            │
   └───────▲────────┘ └──────────────┘ └────────┬─────────┘ └────────▲─────────┘
           │                                     │                    │
           │ (6) read order +                    │ (7) consume        │ (8) index /
           │     stamp search_indexed_at         │     messages        │     search
           │                                     ▼                    │
           │                        ┌─────────────────────────────────┴──────┐
           └────────────────────────┤   JAVA WORKER — java-worker service    │
                                    │   SAME jar, SAME entrypoint, no profile │
                                    │   com.meridian.backend.messaging       │
                                    │   OrderSyncListener (@RabbitListener)  │
                                    │   (backend also runs this listener,    │
                                    │    so both are competing consumers)     │
                                    └────────────────────────────────────────┘
```

### Explaining every arrow

| # | Arrow | What actually happens | Code |
|---|---|---|---|
| — | Browser → Frontend | Vue app served by Vite (dev) or nginx (prod). Bundled SVG artwork is served **by the frontend origin** (`/products/<SKU>.svg`). | `frontend/public/products/`, `frontend/nginx.conf` |
| — | Frontend → Backend | axios `fetch`-style JSON over HTTP. `Content-Type: application/json` forces a **CORS preflight** `OPTIONS` first, then the real request with `Authorization: Bearer <jwt>`. | `frontend/src/api/client.js` |
| (1) | Backend → PostgreSQL | JDBC/Hikari for users, orders, order_items. JPA repositories, `ddl-auto: validate`. | `core/config/DatabaseConfig.java`, `orders/repository/*` |
| (2) | Backend → MongoDB | Spring Data MongoDB + `MongoTemplate` for catalog CRUD, facets, and **reading live prices during checkout**. | `config/MongoConfig.java`, `products/ProductService.java` |
| (3) | Backend → RabbitMQ | After an order is committed, publish `{order_id, reason}` to queue `orders_sync` via topic exchange `orders.sync.exchange`. | `messaging/SyncPublisher.java`, `config/RabbitMQConfig.java` |
| (4) | Backend → Elasticsearch | Admin search (reads) and direct indexing during seed. | `search/SearchService.java`, `search/repository/OrderSearchRepository.java` |
| (5) | Backend → filesystem | Product image upload/delete under `app.uploads.dir` (`/app/uploads`), served back through `/uploads/**`. | `images/ImageService.java`, `config/WebConfig.java` |
| (6) | Worker → PostgreSQL | Reads the full order (items + user) with one fetch-join, then stamps `orders.search_indexed_at`. | `messaging/OrderSyncListener.java` |
| (7) | Worker ← RabbitMQ | `@RabbitListener` on `orders_sync`, manual ack, retry with backoff, `default-requeue-rejected: false`. | `messaging/OrderSyncListener.java` |
| (8) | Worker → Elasticsearch | `indexOrder(doc)` writes one document with `_id = order_id`, then stamps (6). | `search/repository/OrderSearchRepository.java` |

**Two arrows to be careful about:**

* The **backend also listens** to `orders_sync`. `java-worker` is *not* a separate program — it
  is the **same `app.jar`, same `ENTRYPOINT`, no Spring profile, only with no published port**.
  So backend and java-worker are **competing consumers** on one queue (roughly doubling consume
  throughput). See [§7](#7-docker-architecture).
* **Checkout itself talks to Elasticsearch only for reads.** The index is written by the sync
  path (7)–(8), or directly by the seeder.

---

## 3. Database responsibility

### MongoDB — the product catalog

**What lives there:** one document per product in the `products` collection.

| Field | Type | Why it exists |
|---|---|---|
| `_id` | string | Mongo id — this is what `order_items.product_id` stores |
| `sku` | string (unique index `uniq_products_sku`) | human-facing stock code, e.g. `WM-001` |
| `title`, `description` | string | display copy |
| `price` | double | **live** price — re-read on every checkout |
| `category` | string (index `ix_products_category`) | browsing facet |
| `tags` | `string[]` | free-form facets, used for `$all` filtering and tag counts |
| `attributes` | `map<string,any>` | flexible per-category specs (`dpi`, `material`, `switch`…) |
| `variants` | `[{sku,color,stock}]` | nested array — colour/stock options that belong to *this* product |
| `active` | bool (index `ix_products_active`) | soft-delete flag; storefront shows `active:true` only |
| `image_url` | string | pointer to artwork (see [§6](#6-image-architecture)) |
| `created_at`, `updated_at` | `OffsetDateTime` | provenance |

**Why nested/array fields are useful:** a product and its variants are *always* read together and
never queried independently. In SQL you would need `products` + `tags` + `attributes` +
`variants` JOINs just to render one card. In MongoDB the whole product is one document — one
read, no JOIN, and `attributes` can hold a different shape per category without a schema
migration.

**It is the catalog authority:** a product's current price, title and stock live *only* here.
Nothing else is allowed to redefine them. (Orders keep their own frozen copy — see
[§4](#4-order-snapshot-semantics).)

### PostgreSQL — users, orders, order_items

```
users                          orders                         order_items
─────────────────────          ─────────────────────          ─────────────────────
id        INTEGER PK           id          INTEGER PK         id         INTEGER PK
name      VARCHAR(120)         order_number VARCHAR(32) UQ     order_id   FK → orders
email     VARCHAR(255) UQ      user_id     FK → users         product_id VARCHAR(64)
password_hash VARCHAR(255)     order_date  TIMESTAMPTZ        title      VARCHAR(255)
role      VARCHAR(20)          status      VARCHAR(20)        quantity   INTEGER
created_at TIMESTAMPTZ         total_amount NUMERIC(12,2)     unit_price NUMERIC(12,2)
                              created_at / updated_at
                              search_indexed_at (nullable)
```

* **Relationships:** `users 1─n orders 1─n order_items`. Cascade is `ALL` from order → items
  (deleting an order deletes its lines) but `RESTRICT` from user → orders.
* **Transaction boundaries:** `OrderService.createOrder()` is `@Transactional`. Inside one
  transaction it resolves the cart against MongoDB, inserts the `Order` and every `OrderItem`,
  and computes the total. Only after the commit does it publish to RabbitMQ (`finally` block).
  If anything non-business fails, `OrderPersistenceException` is thrown and **no data is
  written**.
* **Order source of truth:** the order detail page, status changes and revenue reporting all
  read PostgreSQL. Elasticsearch is never authoritative for an order.
* **Snapshot semantics:** see [§4](#4-order-snapshot-semantics).

### Elasticsearch — the search projection

**What is indexed:** one document per order in index `orders` (1 shard, 0 replicas), built by
`ElasticsearchOrderDocument`:

```
order_id      long      (also the document _id)
order_number  keyword
status        keyword
order_date    date      (strict_date_optional_time||epoch_millis)
updated_at    date
total_amount  double
customer      object    { id: long, name: search_as_you_type(+keyword),
                          email: search_as_you_type(+keyword) }
items         NESTED    { product_id: keyword,
                          title: search_as_you_type(+keyword),
                          quantity: integer,
                          unit_price: double, line_total: double }
```

**Why it exists:** answering "orders from Wendy in August over ₹200 containing *wireless*,
show revenue and status counts" across thousands of rows in milliseconds is exactly what an
inverted index + aggregations are for. Doing that in SQL needs a full scan plus a `GROUP BY`.

**What it is NOT authoritative for:** everything. The index can be deleted and rebuilt from
PostgreSQL at any time — the seeder does exactly that with `recreateIndex()`. If ES and
PostgreSQL disagree, PostgreSQL is right.

**Search/filter/aggregation responsibility:** all text search, all status/date/price filtering,
pagination totals and the KPI aggregations (`sum(total_amount)` and `terms(status)`) are
computed in ES. Nothing else in the system performs order search.

### RabbitMQ — the synchronization bus

* **What a message represents:** *"order `<id>` changed, reason `<reason>`"* — nothing more.
  Payload class: `SyncPublisher.OrderSyncMessage { orderId, reason }`, reason is
  `order_created` or `status_update`.
* **When produced:** after a successful commit of `POST /api/orders`, and after
  `PATCH /api/orders/{id}/status`. Publishing never throws — failure is reported in the
  response's `sync.error` field rather than failing the user's request.
* **Who consumes:** `OrderSyncListener` (running in **both** the backend and java-worker
  containers), which re-projects the order into Elasticsearch and stamps
  `orders.search_indexed_at`.
* **Topology:** topic exchange `orders.sync.exchange` → durable queue `orders_sync` (routing key
  `orders.sync`). Manual ack, prefetch 1, concurrency 2, 5 retries with exponential backoff
  (10s → ×2 → max 120s), and **no requeue on final failure** (a poison message must not loop
  forever).

### The Java worker

* **What it does:** consumes a sync message, loads the order from PostgreSQL with all items and
  the customer in **one** `JOIN FETCH`, ensures the ES index exists, writes the document, then
  stamps `search_indexed_at` so the UI can show "Indexed".
* **How it updates Elasticsearch:** `OrderSearchRepository.indexOrder()` with
  `_id = order_id` — an idempotent overwrite, so replaying a message is safe.
* **How it differs from the API process:** **structurally, not by code.** Both run the same
  jar; the only differences are (a) `java-worker` publishes no port, and (b) it is a separate
  container, so CPU spent indexing is separate from CPU serving HTTP. There is no separate
  "worker program" in this repository.

---

## 4. Order snapshot semantics

### What an `order_items` row actually stores

| Column | Meaning |
|---|---|
| `product_id` | the **Mongo `_id`** of the product at purchase time (not the SKU) |
| `title` | **frozen copy** of the product title |
| `unit_price` | **frozen copy** of the price at that moment |
| `quantity` | how many were bought |

Note what is *not* stored: the SKU, the description, the category, the image, the live price.

### The concrete example in this system

The seeder demonstrates the point on purpose (`SeedRunner.applySnapshotDemo()`):

| | Value |
|---|---|
| Live MongoDB product `WM-001` **after** the demo | **Wireless Mouse Pro — ₹60.00** |
| Historical PostgreSQL order line recorded **before** the demo | **Wireless Mouse — ₹50.16** |

Both are correct, because they answer different questions:

* *"What does this product cost today?"* → MongoDB: ₹60.00, title "Wireless Mouse Pro".
* *"What did the customer actually pay for this order?"* → PostgreSQL: ₹50.16 for
  "Wireless Mouse".

### Why the catalog must never rewrite history

1. **Money already moved.** The customer was charged ₹50.16. If the order line silently
   followed the catalog to ₹60.00, the order total, any invoice and any refund calculation
   would become wrong — and you could no longer tell what was actually charged.
2. **Orders are an audit record.** "Who bought what, when, for how much" must be answerable
   years later, long after products have been renamed, repriced or discontinued.
3. **Products die, orders don't.** A product can be deactivated or deleted (soft-delete sets
   `active=false`). If orders merely referenced live product rows, deleting a product would
   corrupt every historical order that mentions it. Copying `title` and `unit_price` into the
   line removes that coupling entirely.
4. **It keeps reads simple.** Rendering an order needs no JOIN back to MongoDB — everything
   needed for the line item is already in PostgreSQL.
5. **Search agrees with truth.** The ES document copies the same snapshots, so search results
   show what was paid, not today's price.

**The trade-off, stated honestly:** stored prices can drift from the catalog — that is the
*feature*. The cost is that `order_items` cannot answer "what is this product's price now", so
the checkout path re-reads MongoDB to price a *new* order (`OrderService.resolveCart()` uses
`productRepository.findByIds(...)`, rejects missing/inactive items and out-of-range prices).

---

## 5. Search architecture

### End-to-end shape

```
AdminSearchView.vue
   → searchOrders(payload)                       frontend/src/api/search.js
   → POST /api/search/orders  (Bearer JWT)       SearchController  @PreAuthorize ADMIN
   → SearchService.searchOrders(request)
        buildQuery(request)  ──►  Elasticsearch query DSL
        OrderSearchRepository.search(query, from, size, sort)  ──► ES
        convertResponse(...)  ──►  SearchResponse
   → { results, total, page, limit, pages, tookMs, aggregations }
```

### ES document structure (recap)

Top level: `order_id`, `order_number`, `status`, `order_date`, `updated_at`, `total_amount`.
Nested: `customer {id, name, email}` (an **object**, not nested) and `items {product_id, title,
quantity, unit_price, line_total}` as a true **`nested`** field — required so an item's title can
match on one line while its `unit_price` matches on another.

### `search_as_you_type` — yes, it is really present

`customer.name`, `customer.email` and `items.title` are mapped as `search_as_you_type` (plus a
`keyword` subfield). The omni query uses a **whole-phrase** `multi_match` on
`customer.name^3` / `customer.email`, which is what makes typing an entire email or name narrow
results instead of widening them.

### Query construction — the real methods

All in `search/SearchService.java`:

| Method | Role |
|---|---|
| `buildQuery(SearchRequest)` | Top level: a `bool` whose `must` is the omni text clause (if `query` is non-blank), plus filter clauses. **Falls back to `match_all` when there is nothing to filter on.** |
| `buildOmniQuery(String)` | Splits the query into terms and builds an **AND** across per-term clauses, then adds a whole-phrase `customer` clause and an uppercased `order_number` prefix clause as `should` alternatives, with `minimumShouldMatch(1)`. |
| `termQuery(String)` | Per-term `should`: flat `multi_match` (`order_number^3`, `customer.name^3`), a **nested** `multi_match` over `items.title^2` / `items.product_id`, and an `order_number` prefix clause. |

**Why AND across terms:** with OR semantics, `Wendy Wireless` matched *48* of 48 orders (either
word hit everything). With term-AND it correctly narrows to **6**.

**Filter clauses** (all `filter`, so they do not affect relevance scoring):

| Request field | ES clause |
|---|---|
| `status[]` | `terms: { status: [...] }` |
| `date_from` / `date_to` | `range: { order_date: { gte, lt } }` — `date_to` is **+1 day**, so the end date is inclusive |
| `min_price` / `max_price` | `range: { total_amount: { gte, lte } }` |

> **Implementation note:** range clauses must be built with the typed client builder
> (`.field("...").gte(JsonData.of(...))`). Serialising a literal `{"range":{...}}` string makes
> ES reject the query with a 400.

### Pagination and sorting

`from = (page-1) * limit`, `size = limit`, with `trackTotalHits: true` so `total` is exact
rather than capped at 10,000. Sort is `-order_date, -order_id` (newest first, stable ties).
`pages` is computed as `ceil(total / limit)`.

### Aggregations / KPIs

Run in the **same** ES request, and — importantly — they are scoped to the **filters**, not to
the current page:

| KPI | Aggregation |
|---|---|
| Total revenue | `sum` over `total_amount` |
| Pending / Processing / Shipped counts | `terms` over `status` (size 10) |

`OrderSearchRepository.parseAggregations()` seeds the three statuses to `0` so KPI cards never
render blank.

### Verified behaviour (live, 48 seeded orders)

| Query | Result |
|---|---|
| `match_all` | 48 |
| status `PENDING`, `SHIPPED` | 16 each |
| `[PENDING, PROCESSING]` | 32 |
| `max_price=100` | 15 |
| `min_price=200` | 7 |
| `min_price=1000` | 0 |
| `date_from=2026-09-25` | 9 |
| `date_to=2026-08-01` | 4 |
| Aug range | 20 |
| wireless + SHIPPED + `max_price=200` | 11 |
| paging `limit=5` | total 48, pages 10, results 5 |
| `wendy` | 6 |
| `Wendy Wireless` | 6 (AND, not 48) |
| `wireless` | 48 |
| full email `wendy.wireless@example.com` | 6 |
| exact `ORD-000250` | 1 |
| prefix `ORD-00024` | 5 |
| `mechanical keyboard` | 8 |
| `john doe` | 6 |
| nonsense | 0 |

---

## 6. Image architecture

There are **two distinct image paths**, and the distinction is the key to understanding the
image bug that was found and fixed.

### Path A — bundled artwork (what the seeded catalog uses)

| Aspect | Reality |
|---|---|
| Where metadata lives | MongoDB `products.image_url` (declared in `ProductDocument` as `@Field("image_url")`) |
| Where files live | **The frontend bundle**: `frontend/public/products/<SKU>.svg` |
| URL format | `/products/<SKU>.svg`, e.g. `/products/WM-001.svg` — **relative** |
| Who serves the files | the **frontend** origin (`http://localhost:5173`), not the backend |
| Docker volume | none — these are build-time static assets |
| Formats | SVG only (26 dedicated drawings + `default.svg` placeholder) |

### Path B — admin uploads

| Aspect | Reality |
|---|---|
| Upload endpoint | `POST /api/products/{id}/image` (`multipart/form-data`, field `file`, ADMIN only) |
| Delete endpoint | `DELETE /api/products/{id}/image` (ADMIN only) |
| Where files live | `${app.uploads.dir}/products` → `/app/uploads/products` inside the container |
| Docker volume | `uploads_data:/app/uploads` (mounted on **both** `backend` and `java-worker`) |
| URL format | `/uploads/products/<uuid>.<ext>` |
| How Spring serves it | `WebConfig.addResourceHandlers()`: `/uploads/**` → `file:${app.uploads.dir}/` |
| Allowed formats | `image/jpeg`, `image/png`, `image/webp`, `image/svg+xml` (`.jpg .jpeg .png .webp .svg`) |
| Max size | 5 MB (`ImageService.MAX_FILE_SIZE` and `spring.servlet.multipart.max-file-size`) |
| Filename | `UUID` hex + allowed extension (falls back to `.webp`) |
| Validation | `ImageService.validateImageFile()` → `invalid_image_format` / `image_too_large` |

> The `uploads_data` volume currently contains **no files** — no image has been uploaded through
> the admin UI yet. Only Path A carries data today.

### How the frontend constructs the `<img src>`

`frontend/src/utils/image.js` → `resolveImageUrl(value)`:

1. Not a string / empty → `null` → caller renders the **branded placeholder** (monogram +
   deterministic pattern).
2. Matches an **absolute URL** (`https://…`, `//cdn/…`) or `data:`/`blob:` → returned untouched.
3. Starts with `/uploads/` **and** an API base is configured → prefixed with `API_BASE_URL`
   (because uploads are served by the *backend*).
4. Anything else (bundled assets like `/products/WM-001.svg`) → returned **as-is**, so it
   resolves against the app origin.

`ProductThumb.vue` then does `resolveImageUrl(product.image_url)`; on `null`, load error or
failed load it falls back to the placeholder. It is used by `ProductCard`, `CartDrawer`,
`HeroSection`, `CheckoutLineItems`, `CatalogTable` and `CatalogCardList`.

### The defect that was found — and the fix

**Root cause (category A + E): the seed never wrote `image_url` at all.**

Evidence gathered before the fix:

* MongoDB: **0 of 27** product documents had any `image`/`image_url`/`imageUrl` key.
* `GET /api/products`: `image_url` key present but `null` for **26 of 26** items — so the API
  layer and DTO mapping were fine; the *data* was missing.
* `SeedRunner.seedProducts()` contained no reference to `image` anywhere.
* The artwork *did* exist: `frontend/public/products/` held **26** `<SKU>.svg` files, and a
  stray out-of-band script `update_all_images.py` at the repo root mapped
  `SKU → /products/<SKU>.svg` — but it was never part of the seed, so a re-seed wiped it.
* Reachability was already correct: `GET http://localhost:5173/products/WM-001.svg` →
  `200 image/svg+xml`.

So: **the file was never the problem, the static mapping was never the problem, and the
frontend URL construction was never the problem — the field was simply never populated.**

**Fix applied (smallest durable fix, in `SeedRunner.seedProducts()` only):**

```java
String sku = (String) p.get("sku");
doc.setImageUrl(SKU_WITHOUT_ARTWORK.contains(sku)
    ? DEFAULT_ARTWORK_URL          // "/products/default.svg"
    : "/products/" + sku + ".svg"); // "/products/<SKU>.svg"
```

with `SKU_WITHOUT_ARTWORK = Set.of("CHG-001")` (the one SKU with no dedicated drawing).

No schema change, no API change, no frontend change, no redesign of the image system.

**Verification after the fix:**

| Check | Result |
|---|---|
| Mongo documents with `image_url` | **27 / 27**, none `null` |
| `WM-001` | `/products/WM-001.svg` |
| `CHG-001` | `/products/default.svg` |
| `GET /api/products` non-null `image_url` | **26 / 26** |
| URL fetch — WM-001 / MK-001 / ORG-001 | `200 image/svg+xml` (1578B / 3087B / 1640B) |
| URL fetch — default.svg | `200 image/svg+xml` (1422B) |

*Not verified:* a rendered browser screenshot — no desktop browser is attached to this session,
so confirmation is at data, API and URL level rather than visual.

---

## 7. Docker architecture

Ten services in `docker-compose.yml`.

| Service | Image / build | Purpose | Ports | Depends on | Volumes | Profile |
|---|---|---|---|---|---|---|
| `postgres` | `postgres:16-alpine` | orders, users, order_items | `5432:5432` | — | `postgres_data` | — |
| `mongodb` | `mongo:7` | product catalog | `27017:27017` | — | `mongodb_data` | — |
| `elasticsearch` | `docker.elastic.co/…/elasticsearch:8.15.3` | order search index | `9200:9200` | — | `elasticsearch_data` | — |
| `rabbitmq` | `rabbitmq:3.13-management-alpine` | order sync queue | `5672:5672`, `15672:15672` | — | `rabbitmq_data` | — |
| `backend` | build `./backend-java` | the HTTP API | `8000:8000` | postgres, mongodb, elasticsearch, rabbitmq | `uploads_data:/app/uploads` | — |
| `java-worker` | **same build** | RabbitMQ consumer / indexer | *(none published)* | postgres, elasticsearch, rabbitmq | `uploads_data:/app/uploads` | — |
| `frontend` | build `./frontend` (`target: dev`) | Vite dev server | `5173:5173` | backend | `./frontend:/app`, `frontend_node_modules` | — |
| `frontend-prod` | build `./frontend` (`target: prod`) | nginx static + proxy | `8080:80` | backend | — | `prod` |
| `seed` | **same build** | one-shot database seeder | — | postgres, mongodb, elasticsearch | — | `tools` |
| `reindex` | **same build** | intended one-shot reindexer | — | postgres, elasticsearch | — | `tools` |

Top-level volumes: `postgres_data`, `mongodb_data`, `elasticsearch_data`, `rabbitmq_data`,
`frontend_node_modules`, `uploads_data`.

### Key environment variables

Shared by `backend` and `java-worker` via the `x-backend-environment` anchor:

```
POSTGRES_HOST=postgres            POSTGRES_PORT=5432
MONGO_URI=mongodb://mongodb:27017
SPRING_DATA_MONGODB_URI=mongodb://mongodb:27017
ELASTICSEARCH_URL=http://elasticsearch:9200
RABBITMQ_HOST=rabbitmq
RABBITMQ_URL=amqp://<user>:<pass>@rabbitmq:5672/
SERVER_PORT=8000
```

Frontend: `VITE_API_BASE_URL` (default `http://localhost:8000`) and `VITE_PROXY_TARGET`
(`http://backend:8000`).

Also relevant: `app.cors.allowed-origins` (default `http://localhost:5173`),
`app.uploads.dir` (`/app/uploads`), `app.jwt.secret`, `app.jwt.expiration-minutes` (1440).

### Internal Docker hostnames vs `localhost`

This trips people up constantly:

| From | To a container | To the host |
|---|---|---|
| Browser / host shell | use **published ports**: `localhost:8000`, `localhost:5173`, `localhost:9200` | — |
| Another container on `app_net` | use the **service name**: `postgres`, `mongodb`, `elasticsearch`, `rabbitmq`, `backend` | `host.docker.internal` (not configured here) |
| The backend itself | `mongodb:27017`, `elasticsearch:9200`, `rabbitmq:5672` | — |

So: **`localhost` never resolves to a sibling container.** `spring.data.mongodb.uri` inside the
backend is `mongodb://mongodb:27017`, not `localhost:27017`.

Conversely `VITE_API_BASE_URL=http://localhost:8000` is correct *because it is evaluated in the
browser*, where `localhost` is the host machine.

### Run commands

```bash
docker compose up -d                                   # infra + backend + java-worker + frontend
docker compose build backend seed                      # required before re-seeding
docker compose --profile tools run --rm seed           # re-seed (never exits — see caveats)
docker compose --profile tools run --rm reindex        # ⚠ currently a no-op (see caveats)
docker compose --profile prod up -d --build frontend-prod   # nginx frontend on :8080
```

---

## 8. API map

**16 application endpoints** (15 business + health), enumerated by scanning every `@GetMapping` /
`@PostMapping` / `@PatchMapping` / `@DeleteMapping` under `backend-java/src/main/java`.
Two framework `/actuator/*` endpoints also exist but require auth (see caveats).

`AUTH` column: **public** = no token needed; **authenticated** = any valid JWT;
**ADMIN** = `ROLE_ADMIN`.

| METHOD | PATH | PURPOSE | AUTH | DATABASE / SYSTEM |
|---|---|---|---|---|
| POST | `/api/auth/login` | Verify email+password, issue JWT | public | PostgreSQL |
| POST | `/api/auth/logout` | Acknowledge logout (stateless — nothing revoked) | public | — |
| GET | `/api/auth/me` | Current user from the token | public* | PostgreSQL |
| GET | `/api/products` | List/filter/sort/paginate catalog | public | MongoDB |
| GET | `/api/products/facets` | Category + tag facet counts | public | MongoDB |
| GET | `/api/products/{id}` | Single product | public | MongoDB |
| POST | `/api/products` | Create product | ADMIN† | MongoDB |
| PATCH | `/api/products/{id}` | Partial update | ADMIN† | MongoDB |
| DELETE | `/api/products/{id}` | Soft delete (`active=false`) | ADMIN† | MongoDB |
| POST | `/api/products/{id}/image` | Upload image (multipart) | ADMIN† | Filesystem + MongoDB |
| DELETE | `/api/products/{id}/image` | Remove image | ADMIN† | Filesystem + MongoDB |
| POST | `/api/orders` | Checkout — create order | authenticated | PostgreSQL + MongoDB + RabbitMQ |
| GET | `/api/orders/{id}` | Order detail (ownership enforced) | authenticated | PostgreSQL |
| PATCH | `/api/orders/{id}/status` | Change order status | ADMIN | PostgreSQL + RabbitMQ |
| POST | `/api/search/orders` | Admin search + filters + KPIs | ADMIN | Elasticsearch |
| GET | `/api/health` | Dependency health + latency | public | all four (read-only) |

\* `GET /api/auth/me` is `permitAll`, so calling it *without* a token reaches the controller with
a `null` principal and returns **500** instead of 401 — see [§10](#10-known-caveats).

† ADMIN is enforced by **method-level** `@PreAuthorize("hasRole('ADMIN')")`, not by the URL rules
— the URL rules for these paths are shadowed by an earlier `permitAll`. See
[§10](#10-known-caveats) for the status-code consequence.

### URL authorization rules as declared (`SecurityConfig.securityFilterChain`)

```
permitAll  /api/auth/** , /api/health , /api/users
permitAll  /api/products , /api/products/facets , /api/products/** , /uploads/**
authenticated  POST /api/orders , GET /api/orders/**
hasRole(ADMIN)  PATCH /api/orders/**/status , /api/search/**
hasRole(ADMIN)  POST /api/products , PATCH /api/products/** , DELETE /api/products/**
               POST /api/products/**/image , DELETE /api/products/**/image
anyRequest -> authenticated
```

Matchers are evaluated **in order and the first match wins** — so the `permitAll` lines for
`/api/products` (any method) precede and shadow the ADMIN lines for the same paths. The
`@PreAuthorize` annotations on the controller methods are therefore what actually protects
product writes.

---

## 9. Important design decisions

| Decision | Why |
|---|---|
| **MongoDB for the catalog** | Products are nested documents (`tags[]`, `attributes{}`, `variants[]`) always read whole. One read, no JOIN, and `attributes` can vary by category without schema changes. |
| **PostgreSQL for orders** | Orders must be ACID, referentially intact and permanently auditable. `@Transactional` checkout, FK constraints and `NUMERIC` money types are exactly right for a source of truth. |
| **Elasticsearch for search** | Filtering + aggregating across orders is an inverted-index problem. Doing it in SQL would mean scans and `GROUP BY`s on every dashboard load, and ES gives exact `track_total_hits` pagination for free. |
| **RabbitMQ for synchronization** | Decouples "order placed" (must be fast and durable) from "search updated" (can lag). Publish-after-commit keeps checkout fast even if the search cluster is slow or down, and the durable queue survives a backend restart. |
| **Java worker for the ES projection** | Keeps indexing off the request thread and lets indexing scale independently of HTTP. Because it shares the jar, the projection logic has exactly one implementation — no drift between API and worker. |
| **Snapshots for historical order data** | `title` and `unit_price` are copied into `order_items` so catalog changes can never rewrite what a customer paid (see [§4](#4-order-snapshot-semantics)). |
| **JWT for authentication** | Stateless sessions: no server-side session store, any number of backend replicas can validate a token, and the browser just stores a string. Stateless also means **logout is client-side only** — the token stays valid until `exp`. |
| **BCrypt for passwords** | Adaptive, salted hashing — a stolen `password_hash` cannot be reversed, and cost can be raised later without invalidating existing hashes. Seeded hashes are `$2a$10$…`, 60 chars. |
| **Manual Mongo wiring (`MongoConfig`)** | `MongoAutoConfiguration` is excluded so `OffsetDateTime ⇄ BSON Date` conversion is explicit and deterministic. A prior bug here caused a codec failure; the fix is a `MongoCustomConversions` pair, deliberately **not** a custom `CodecProvider`. |
| **`search_as_you_type` + `nested` items** | `search_as_you_type` gives as-you-type/phrase matching over customer and item titles; `nested` is required so item fields match per-line rather than across lines. |
| **Two-phase order number (`TMP-` → `ORD-%06d`)** | `order_number` is `NOT NULL` and unique, but the sequence value is only known after the first insert, so a provisional `TMP-<uuid>` is written first and replaced with `ORD-%06d` in the same transaction. |
| **Soft delete for products** | `active=false` keeps historical orders and search results intact instead of orphaning them. |

---

## 10. Known caveats

Taken from the latest [`FINAL_SYSTEM_AUDIT.md`](FINAL_SYSTEM_AUDIT.md) plus findings from this
verification pass. Nothing here is hidden or softened.

### Fixed in this pass

| Caveat | Status |
|---|---|
| `OPTIONS` preflight returned **403 `Invalid CORS request`** while simple `GET`s returned 200 | **FIXED** — see [`SYSTEM_WORKFLOW.md` Appendix C](SYSTEM_WORKFLOW.md#appendix-c--admin-login-issue-diagnosis) for the full root cause |
| Product images not visible (`image_url` always `null`) | **FIXED** — see [§6](#6-image-architecture) |
| Frontend test suite **25 passed / 3 failed** (all CORS-caused) | **NOW 28 passed / 0 failed** (`vitest run`, 5 files) |

### Open — pre-existing, deliberately not changed

| # | Caveat | Evidence / impact |
|---|---|---|
| 1 | **No *Java* backend test suite.** `backend-java/src/test` contains **0 files** (the directory tree exists but is empty) — `mvn test` → "No tests to run". | The 16 endpoints, the auth matrix and the snapshot logic are covered only by manual verification. |
| 1b | **The repository ships two backends, and the README documents the wrong one.** `backend/` is a **legacy Python FastAPI service** (51 `.py` files) that `docker-compose.yml` never builds — every `build.context` is `./backend-java`. `README.md` still describes FastAPI (`http://localhost:8000/docs` Swagger, `python -m pytest` (101 tests)), and `docs/MERIDIAN_COMPLETE_SYSTEM_KNOWLEDGE.md` documents `backend/app/api/products.py`. Root `tests/` (9 Python files) + `pytest.ini` target that legacy service; `pytest` is not installed in this environment, so those tests were never run here. | A reader following `README.md` would start the wrong service. The running system is the **Java** one — see [`FILE_BY_FILE_GUIDE.md`](FILE_BY_FILE_GUIDE.md) §0. |
| 2 | **RabbitMQ → worker path has never been exercised by a live checkout end to end.** | The seeder indexes orders directly (`reindexSearch()`), which bypasses the queue. Publishing and listening are individually wired and the topology exists, but no automated test drives checkout → queue → worker → ES. |
| 3 | **Seed keeps Tomcat alive.** `docker compose --profile tools run --rm seed` never returns; `EXIT=124` is the normal timeout, not a failure. | Success must be judged by the log marker `Seed data initialization completed!`. |
| 4 | **One future-dated order** — `future_dated_orders = 1` (≈14 h ahead). | Pre-existing in seed data: `ageDays(5)==0` combined with `.withHour((5*13)%24)`. Harmless but visible in date filters. |
| 5 | **`GET /api/auth/me` without a token → 500.** | `/api/auth/**` is `permitAll`, so `@AuthenticationPrincipal User` is `null` and `AuthService.mapToUserResponse()` NPEs. Log: `Cannot invoke "…User.getId()" because "user" is null`. Should be 401. The frontend never hits this (`fetchMe()` no-ops without a token). |
| 6 | **Forbidden admin writes → 500 instead of 403/401.** `POST /api/products` as a customer or anonymously returns **500**. | `@PreAuthorize` throws `AccessDeniedException`, but `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)` converts it to `500 internal_error` before Spring Security's `ExceptionTranslationFilter` can map it to 403/401. **The request is still correctly denied** (verified: admin could immediately create the same SKU afterwards, proving no write occurred) — only the status code is wrong. Root cause is the same shadowing noted in [§8](#8-api-map): URL rules match `permitAll` first, so enforcement falls through to method security. |
| 7 | **Unsupported HTTP method → 500 instead of 405.** e.g. `GET /api/orders` → 500. | `HttpRequestMethodNotSupportedException` is swallowed by the same catch-all. Confirmed: `Unhandled error: Request method 'GET' is not supported`. |
| 8 | **The `reindex` compose service is a no-op.** | It runs `java -jar app.jar --spring.profiles.active=reindex`, but `@Profile("seed")` on `SeedRunner` is the **only** `@Profile` in the codebase and there is no `application-reindex.yml`. The app starts, does no reindexing, and idles. |
| 9 | **`java-worker` is not a distinct worker program.** | Same image, same `ENTRYPOINT ["java","-jar","app.jar"]`, no profile, only no published port. Backend and java-worker are **competing consumers** on `orders_sync`. The design docs should not imply a separate worker codebase. |
| 10 | **Cart is not persisted.** `stores/cart.js` has no `localStorage` write. | Reloading the page empties the cart. |
| 11 | **No product detail page.** There is no `/products/:id` route. | Product information only appears in cards, hero, cart lines, checkout lines and the admin tables. |
| 12 | **`order_items.product_id` is the Mongo `_id`, not the SKU.** | Joining a line back to a human-readable SKU requires a Mongo lookup. |
| 13 | **`app.jwt.algorithm: HS256` is declared but never read.** | `JwtTokenProvider` calls `signWith(secretKey)`, so jjwt infers the algorithm from key length. Observed tokens are actually **`{"alg":"HS384"}`**. |
| 14 | **Dead code / unused wiring** (harmless, noted for accuracy). | `OrderService.listUsers()` has no mapped `GET /api/users` (though `permitAll` allows the path); `ProductService.ensureIndexes()` is an empty uncalled method; `PaginationMeta` is unreferenced; several repository query methods have no callers; `SyncPublisher` is injected into `SearchService` but unused; `@EnableAsync`/`@EnableScheduling` exist with no `@Async`/`@Scheduled` methods; frontend `api/users.js` is imported by nothing. |
| 15 | **No visual browser verification for this pass.** | No desktop browser is attached to this session, so image rendering and login were verified at data, API and URL level, not by a screenshot. |

### System state at the time of writing

| Fact | Value |
|---|---|
| Containers | 7 up, all healthy (`postgres`, `mongodb`, `elasticsearch`, `rabbitmq`, `backend`, `java-worker`, `frontend`) |
| Users | 9 (8 `CUSTOMER` + 1 `ADMIN`) |
| Products | 27 documents, 26 active, 1 inactive (`CHG-001`) |
| Products with `image_url` | **27 / 27** |
| Orders | 48 (`ORD-000293` … `ORD-000340`; the range **advances on every seed run**) |
| `order_items` | 107, spread over all 48 orders |
| Null `order_number` | **0** |
| Status split | PENDING 16 / PROCESSING 16 / SHIPPED 16 |
| Revenue | ₹2390.96 / ₹1947.52 / ₹2693.96 (total ₹7032.44) |
| Elasticsearch `orders` docs | 48 |
| Future-dated orders | 1 |
| Frontend tests | 28 passed / 0 failed |

---

*Next:* [`SYSTEM_WORKFLOW.md`](SYSTEM_WORKFLOW.md) for step-by-step request flows,
[`FILE_BY_FILE_GUIDE.md`](FILE_BY_FILE_GUIDE.md) for the source-level tour.
