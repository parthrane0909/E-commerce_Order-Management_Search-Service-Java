<template>
  <div class="storefront-view">
    <HeroSection class="reveal reveal--1" />

    <CategorySection
      class="reveal reveal--2"
      :categories="facets.categories"
      :loading="facetsLoading && !facets.categories.length"
    />

    <TagMarquee
      v-if="facets.tags.length"
      class="reveal reveal--3"
      :tags="facets.tags"
      :clickable="true"
    />

    <!-- Product collection -->
    <section
      id="products-section"
      class="collection reveal reveal--4"
      aria-labelledby="collection-heading"
    >
      <header class="collection__head">
        <div class="collection__titles">
          <p v-if="searchTerm" class="collection__eyebrow">Search results</p>
          <h2 id="collection-heading" class="collection__title">
            <template v-if="searchTerm">
              “{{ searchTerm }}”
            </template>
            <template v-else-if="category">
              {{ prettyCategory(category) }}
            </template>
            <template v-else>
              The full collection
            </template>
          </h2>
          <p class="collection__meta">
            <template v-if="loading">Loading products…</template>
            <template v-else>
              <span class="collection__count tabular">{{ products.total }}</span>
              {{ products.total === 1 ? 'product' : 'products' }}
              <template v-if="searchTerm"> matching your search</template>
              <template v-else-if="category"> in {{ prettyCategory(category) }}</template>
            </template>
          </p>
        </div>

        <div class="collection__tools">
          <div v-if="searchTerm || category || sort !== 'newest'" class="collection__chips">
            <button v-if="searchTerm" type="button" class="chip" @click="clearQuery('q')">
              “{{ searchTerm }}”
              <AppIcon name="close" :size="13" />
            </button>
            <button v-if="category" type="button" class="chip" @click="clearQuery('category')">
              {{ prettyCategory(category) }}
              <AppIcon name="close" :size="13" />
            </button>
            <button
              v-if="sort !== 'newest'"
              type="button"
              class="chip"
              @click="onSortChange('newest')"
            >
              Sorted
              <AppIcon name="close" :size="13" />
            </button>
          </div>

          <ProductFilters
            :category="category"
            :sort="sort"
            :category-facets="facets.categories"
            @update:category="onCategoryChange"
            @update:sort="onSortChange"
            @reset="resetFilters"
          />
        </div>
      </header>

      <ProductGrid
        :items="products.items"
        :loading="loading"
        :error="error"
        @add="addToCart"
        @clear="resetFilters"
        @retry="loadFirstBatch"
      />

      <!--
        Infinite-scroll sentinel. It is always rendered (only its content toggles) so the
        IntersectionObserver stays bound to one stable node across filter changes.
        Minimal status copy only: loading more, or the end of the results.
      -->
      <div
        ref="loadMoreSentinel"
        class="collection__sentinel"
        data-testid="load-more-sentinel"
        aria-live="polite"
      >
        <span v-if="loadingMore" data-testid="loading-more">Loading more products…</span>
        <span v-else-if="endOfResults" data-testid="end-of-results">You’ve reached the end.</span>
      </div>
    </section>

    <!-- Promotional band -->
    <section class="promo reveal reveal--5" aria-label="Why shop at Meridian">
      <article v-for="item in promos" :key="item.title" class="promo__item">
        <span class="promo__icon" aria-hidden="true">
          <AppIcon :name="item.icon" :size="22" />
        </span>
        <div>
          <h3 class="promo__title">{{ item.title }}</h3>
          <p class="promo__text">{{ item.text }}</p>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/common/AppIcon.vue'
import ProductFilters from '../components/storefront/ProductFilters.vue'
import ProductGrid from '../components/storefront/ProductGrid.vue'
import HeroSection from '../components/storefront/HeroSection.vue'
import CategorySection from '../components/storefront/CategorySection.vue'
import TagMarquee from '../components/storefront/TagMarquee.vue'
import { listProducts, getProductFacets } from '../api/products'
import { useCartStore } from '../stores/cart'
import { useToastStore } from '../stores/toast'

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const toast = useToastStore()

/** Batch size. One request fills three rows of the four-up grid, then scrolling appends. */
const PAGE_SIZE = 12

/** How far ahead of the viewport the next batch is requested — feels instant, costs one call. */
const SENTINEL_ROOT_MARGIN = '600px 0px'

const promos = [
  { icon: 'truck', title: 'Free shipping', text: 'On every order over ₹5,000' },
  { icon: 'undo', title: '30-day returns', text: 'Changed your mind? Send it back' },
  { icon: 'shield', title: 'Secure checkout', text: 'JWT-protected payments & orders' },
  { icon: 'box', title: 'Fast dispatch', text: 'Packed within one working day' },
]

// URL-driven filter state — single source of truth.
// There is no page state any more: the listing is cursor-paginated, so the URL only
// carries the query (q / category / sort). Legacy `?page=` links are stripped on mount.
const category = ref(String(route.query.category || ''))
const sort = ref(String(route.query.sort || 'newest'))

const facets = ref({ categories: [], tags: [] })
const facetsLoading = ref(false)
const facetsError = ref('')

const products = ref({ items: [], total: 0, pages: 0, page: 1, limit: PAGE_SIZE })

/** Opaque continuation token for the next batch; null means we cannot ask for more. */
const nextCursor = ref(null)
/** Server's "another batch exists" flag. Drives the sentinel and the end-of-results copy. */
const hasMore = ref(false)
/** First batch / filter reset → the grid skeleton. Never true while appending. */
const loading = ref(false)
/** Appending → the inline "Loading more products…" line. The grid stays on screen. */
const loadingMore = ref(false)
const error = ref('')

/**
 * Request bookkeeping. Every fetch stamps the generation it belongs to and takes the
 * newest sequence number; a response is applied only if both still match. That single
 * rule drops late responses from a filter the user already changed away from, and stops
 * two overlapping loads from interleaving.
 */
let generation = 0
let requestSeq = 0
/** Folds the duplicate triggers a single user action can raise (route sync + local handler). */
let inFlightKey = ''

const searchTerm = computed(() => String(route.query.q || '').trim())

const routeCategory = computed(() => String(route.query.category || ''))
const routeSort = computed(() => String(route.query.sort || 'newest'))

/** True once nothing more can be appended and there is something on screen to say it about. */
const endOfResults = computed(
  () => !loading.value && !loadingMore.value && products.value.items.length > 0 && !hasMore.value,
)

function prettyCategory(value) {
  const raw = String(value || '')
  return raw.charAt(0).toUpperCase() + raw.slice(1)
}

function pushQuery(patch) {
  const query = { ...route.query }
  Object.entries(patch).forEach(([key, value]) => {
    if (value === undefined || value === '' || value === null) delete query[key]
    else query[key] = String(value)
  })
  router.replace({ query }).catch(() => {})
}

/* ---------------- route -> local sync ---------------- */

watch(routeCategory, (value) => {
  if (value !== category.value) category.value = value
})

watch(routeSort, (value) => {
  if (value !== sort.value) sort.value = value
})

/* ---------------- local controls ---------------- */

function onCategoryChange(value) {
  category.value = value
  pushQuery({ category: value || undefined })
  scrollToCollection()
}

function onSortChange(value) {
  sort.value = value
  pushQuery({ sort: value === 'newest' ? undefined : value })
}

function clearQuery(key) {
  if (key === 'q') {
    pushQuery({ q: undefined })
    return
  }
  if (key === 'category') {
    category.value = ''
    pushQuery({ category: undefined })
  }
}

function resetFilters() {
  category.value = ''
  sort.value = 'newest'
  router.replace({ query: {} }).catch(() => {})
  // When the query was already empty this replace is a duplicate navigation, so the
  // filter watcher never fires — load directly. Duplicate triggers in one tick are
  // folded together by the in-flight key guard in loadFirstBatch().
  loadFirstBatch()
}

function scrollToCollection() {
  const el = document.querySelector('#products-section')
  if (!el) return
  const top = el.getBoundingClientRect().top + window.scrollY - 96
  const behavior =
    window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ? 'auto' : 'smooth'
  window.scrollTo({ top, behavior })
}

/* ---------------- infinite scroll ---------------- */

const loadMoreSentinel = ref(null)
let observer = null
/** Whether the sentinel currently sits inside the root margin — checked before every load. */
let sentinelVisible = false

function observeSentinel() {
  if (observer) observer.disconnect()
  sentinelVisible = false
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(
    (entries) => {
      sentinelVisible = entries.some((entry) => entry.isIntersecting)
      if (sentinelVisible) loadMore()
    },
    { rootMargin: SENTINEL_ROOT_MARGIN },
  )
  if (loadMoreSentinel.value) observer.observe(loadMoreSentinel.value)
}

function disconnectSentinel() {
  if (observer) observer.disconnect()
  observer = null
  sentinelVisible = false
}

/**
 * Appends the next batch. Never replaces, never runs twice at once, and only fires
 * when the server actually gave us a continuation token.
 */
async function loadMore() {
  if (loading.value || loadingMore.value) return
  if (!hasMore.value || !nextCursor.value) return
  await fetchBatch({ reset: false })
}

/* ---------------- fetching ---------------- */

function buildParams() {
  const params = { limit: PAGE_SIZE, sort: sort.value, visibility: 'active' }
  const term = route.query.q?.trim()
  if (term) params.q = term
  if (category.value) params.category = category.value
  return params
}

/**
 * The only fetch path. `reset` starts a fresh result set (list is cleared by the
 * caller); otherwise the batch is appended to whatever is already on screen.
 */
async function fetchBatch({ reset }) {
  const stamp = generation
  const seq = (requestSeq += 1)

  const params = buildParams()
  if (!reset && nextCursor.value) params.cursor = nextCursor.value

  if (reset) {
    loading.value = true
    error.value = ''
  } else {
    loadingMore.value = true
  }

  try {
    const data = await listProducts(params)
    // A newer request owns the UI — drop this response on the floor.
    if (stamp !== generation || seq !== requestSeq) return

    const incoming = (data.items || []).filter(
      (item) => item && (item.id || item.sku) && !seenIds().has(item.id || item.sku),
    )

    products.value = {
      ...data,
      items: reset ? (data.items || []) : [...products.value.items, ...incoming],
    }
    nextCursor.value = data.next_cursor || null
    hasMore.value = Boolean(data.has_more) && Boolean(data.next_cursor)
    error.value = ''

    // The observer only reports *crossings*. If the sentinel was already inside the
    // band when this batch landed (tall viewport / short first page), nudge it once so
    // loading continues until the sentinel actually leaves the band. The "grew" check
    // is what stops a batch of nothing from spinning forever.
    const grew = reset ? products.value.items.length > 0 : incoming.length > 0
    if (grew && sentinelVisible && hasMore.value) {
      nextTick(() => loadMore())
    }
  } catch (requestError) {
    if (stamp !== generation || seq !== requestSeq) return
    const code = requestError?.response?.data?.error?.code
    if (!reset && code === 'invalid_cursor') {
      // The cursor no longer matches the query. Discard it and restart cleanly
      // instead of stranding the user on a dead end.
      nextCursor.value = null
      hasMore.value = false
      loadFirstBatch()
      return
    }
    error.value = requestError?.message || 'Could not load products.'
  } finally {
    if (stamp === generation && seq === requestSeq) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

function seenIds() {
  const seen = new Set()
  for (const item of products.value.items) {
    const key = item && (item.id || item.sku)
    if (key) seen.add(key)
  }
  return seen
}

/** Clears the list and the cursor, then loads the first batch for the current query. */
async function loadFirstBatch() {
  const key = JSON.stringify(buildParams())
  if (loading.value && key === inFlightKey) return
  inFlightKey = key

  generation += 1
  nextCursor.value = null
  hasMore.value = true
  loadingMore.value = false
  products.value = { items: [], total: 0, pages: 0, page: 1, limit: PAGE_SIZE }

  // Re-binding the observer re-runs its first callback against the current layout,
  // so a changed query always re-evaluates whether more results are needed.
  observeSentinel()
  await fetchBatch({ reset: true })
}

async function loadFacets() {
  facetsLoading.value = true
  facetsError.value = ''
  try {
    facets.value = await getProductFacets()
  } catch (requestError) {
    facetsError.value = requestError?.message || 'Facets unavailable.'
  } finally {
    facetsLoading.value = false
  }
}

// Any filter change discards the list, the cursor and any in-flight append, then
// restarts from the first batch. The cursor is never carried across a query change.
watch([() => route.query.q, category, sort], loadFirstBatch, { immediate: true })

onMounted(() => {
  // Page numbers are history — drop them so the URL only ever carries filters.
  if (route.query.page !== undefined) pushQuery({ page: undefined })
  observeSentinel()
  loadFacets()
})

onBeforeUnmount(disconnectSentinel)

function addToCart(product) {
  cart.add(product)
  toast.success(`“${product.title}” added to your cart.`)
}
</script>

<style scoped>
.storefront-view {
  display: flex;
  flex-direction: column;
  gap: 0;
  min-width: 0;
}

/* ---------------- entrance animation ---------------- */

.reveal {
  animation: rise 620ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.reveal--1 {
  animation-delay: 40ms;
}
.reveal--2 {
  animation-delay: 130ms;
}
.reveal--3 {
  animation-delay: 210ms;
}
.reveal--4 {
  animation-delay: 280ms;
}
.reveal--5 {
  animation-delay: 350ms;
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(22px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* ---------------- collection ---------------- */

.collection {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  width: 100%;
  min-width: 0;
  scroll-margin-top: 96px;
}

.collection__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-5);
  flex-wrap: wrap;
}

.collection__titles {
  min-width: 0;
}

.collection__eyebrow {
  margin: 0 0 var(--space-1);
  font-size: var(--text-xs);
  font-weight: var(--fw-bold);
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--color-primary-hover);
}

.collection__title {
  margin: 0;
  font-size: var(--text-2xl);
  line-height: 1.2;
  font-weight: var(--fw-bold);
  letter-spacing: -0.02em;
  color: var(--color-text);
  overflow-wrap: anywhere;
}

.collection__meta {
  margin: var(--space-1) 0 0;
  font-size: var(--text-sm);
  color: var(--color-text-muted);
}

.collection__count {
  font-weight: var(--fw-semibold);
  color: var(--color-text);
}

.collection__tools {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.collection__chips {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

/* Scroll sentinel — reserves a little space so the layout never jumps when the
   status line swaps between loading and end-of-results. */
.collection__sentinel {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 40px;
  margin-top: var(--space-5);
  font-size: var(--text-sm);
  color: var(--color-text-muted);
  text-align: center;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 var(--space-3);
  border: 1px solid var(--color-ink);
  border-radius: var(--radius-full);
  background: var(--color-ink);
  color: #fff;
  font-size: var(--text-sm);
  font-weight: var(--fw-medium);
  cursor: pointer;
  max-width: 260px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: background-color var(--transition), color var(--transition);
}

.chip:hover {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-ink);
}

/* ---------------- promo band ---------------- */

.promo {
  margin-top: var(--space-9);
  padding: var(--space-6);
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.promo__item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  min-width: 0;
}

.promo__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  flex: none;
  border-radius: var(--radius);
  background: var(--color-primary-soft);
  color: var(--color-primary-hover);
}

.promo__title {
  margin: 0 0 2px;
  font-size: var(--text-base);
  font-weight: var(--fw-semibold);
  color: var(--color-text);
}

.promo__text {
  margin: 0;
  font-size: var(--text-sm);
  line-height: 1.45;
  color: var(--color-text-muted);
}

/* ---------------- responsive ---------------- */

@media (max-width: 1024px) {
  .promo {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .collection__head {
    flex-direction: column;
    align-items: stretch;
    gap: var(--space-4);
  }

  .collection__title {
    font-size: var(--text-xl);
  }

  .collection__tools {
    flex-direction: column;
    align-items: stretch;
  }

  .promo {
    margin-top: var(--space-7);
    padding: var(--space-5) var(--space-4);
    grid-template-columns: 1fr;
    gap: var(--space-4);
  }
}

@media (prefers-reduced-motion: reduce) {
  .reveal {
    animation: none !important;
    opacity: 1 !important;
    transform: none !important;
  }
}
</style>
