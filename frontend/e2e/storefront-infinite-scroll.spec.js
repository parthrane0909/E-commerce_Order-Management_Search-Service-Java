import { test, expect } from '@playwright/test'

/**
 * Storefront infinite scroll — verification only.
 *
 * Everything here runs against the live API; nothing is mocked and no data is
 * inserted from Playwright. The suite proves the four behaviours the feature
 * promises: batches append (never replace), the cursor is dropped when the query
 * changes, progress/race guards hold, and the walk terminates with the end-of-results
 * status instead of a page-number control.
 */

const cards = (page) => page.locator('[data-testid="product-card"]')
const sentinel = (page) => page.locator('[data-testid="load-more-sentinel"]')
const loadingMore = (page) => page.locator('[data-testid="loading-more"]')
const endOfResults = (page) => page.locator('[data-testid="end-of-results"]')

function trackListRequests(page) {
  const urls = []
  page.on('request', (request) => {
    const url = request.url()
    if (url.includes('/api/products?') && !url.includes('/facets')) urls.push(url)
  })
  return urls
}

async function ids(page) {
  return cards(page).evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-product-id')))
}

/**
 * Drives a guaranteed intersection crossing: park at the top (sentinel leaves the
 * 600px band) then centre the sentinel (it re-enters). One crossing => at most one
 * appended batch, which keeps request counts measurable.
 */
async function requestMore(page) {
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
  await page.waitForTimeout(150)
  await page.evaluate(() => {
    const node = document.querySelector('[data-testid="load-more-sentinel"]')
    if (node) node.scrollIntoView({ block: 'center', behavior: 'instant' })
  })
  await page.waitForTimeout(150)
}

async function growTo(page, target, maxSteps = 30) {
  for (let step = 0; step < maxSteps; step += 1) {
    const current = await cards(page).count()
    if (current >= target) return current
    await requestMore(page)
  }
  return cards(page).count()
}

test.describe('storefront infinite scroll', () => {
  test('scrolling appends batches with no duplicates and no page controls', async ({ page }) => {
    const requests = trackListRequests(page)

    await page.goto('/')
    await expect(cards(page).first()).toBeVisible()
    await expect.poll(() => cards(page).count(), { timeout: 20_000 }).toBeGreaterThanOrEqual(1)

    const initial = await cards(page).count()
    expect(initial).toBeGreaterThan(0)
    expect(initial).toBeLessThanOrEqual(12)

    const grown = await growTo(page, 48)
    expect(grown).toBeGreaterThanOrEqual(48)

    // Batches append — the grid only ever grows.
    expect(grown).toBeGreaterThanOrEqual(initial)

    const seen = await ids(page)
    expect(seen).toHaveLength(grown)
    expect(new Set(seen).size, 'duplicate product id rendered').toBe(seen.length)
    expect(seen.every(Boolean)).toBe(true)

    // One request for the first batch plus one per 12-item batch. A request storm
    // (or a refetch-per-render) would blow well past this.
    const expectedBatches = 1 + Math.ceil((grown - initial) / 12)
    expect(requests.length).toBeLessThanOrEqual(expectedBatches + 2)
    expect(requests.length).toBeGreaterThanOrEqual(2)

    // Page-number UI is gone from the storefront.
    await expect(page.locator('nav.pagination')).toHaveCount(0)
    await expect(page.getByText(/page \d+ of \d+/i)).toHaveCount(0)
    expect(page.url()).not.toContain('page=')
  })

  test('an appended batch reports progress while it is in flight', async ({ page }) => {
    let delayed = 0
    await page.route('**/api/products*', async (route) => {
      if (route.request().url().includes('cursor=')) {
        delayed += 1
        await new Promise((resolve) => setTimeout(resolve, 1_500))
      }
      await route.continue()
    })

    await page.goto('/')
    await expect(cards(page).first()).toBeVisible()
    await expect.poll(() => cards(page).count(), { timeout: 20_000 }).toBeGreaterThanOrEqual(1)
    const before = await cards(page).count()

    await requestMore(page)

    await expect(loadingMore(page)).toBeVisible()
    await expect.poll(() => cards(page).count(), { timeout: 20_000 }).toBeGreaterThan(before)
    await expect(loadingMore(page)).toBeHidden()
    expect(delayed).toBeGreaterThan(0)

    const seen = await ids(page)
    expect(new Set(seen).size).toBe(seen.length)
  })

  test('a filtered query walks to the end and says so', async ({ page }) => {
    await page.goto('/?q=pet%20bed')
    await expect(cards(page).first()).toBeVisible()
    await expect.poll(() => cards(page).count(), { timeout: 20_000 }).toBe(12)

    for (let step = 0; step < 8 && (await endOfResults(page).count()) === 0; step += 1) {
      await requestMore(page)
    }

    await expect(endOfResults(page)).toBeVisible()
    await expect(loadingMore(page)).toBeHidden()

    const seen = await ids(page)
    expect(seen).toHaveLength(16)
    expect(new Set(seen).size).toBe(16)
    expect(page.url()).not.toContain('page=')
  })

  test('changing the query restarts from the first batch with a fresh cursor', async ({ page }) => {
    const requests = trackListRequests(page)

    await page.goto('/')
    await expect(cards(page).first()).toBeVisible()
    await expect.poll(() => cards(page).count(), { timeout: 20_000 }).toBeGreaterThanOrEqual(1)
    await growTo(page, 24)

    expect(requests.some((url) => url.includes('cursor=')), 'append never used a cursor').toBe(true)

    // Open the (collapsed-by-default) filter panel and switch sort order.
    await page.locator('.filters__toggle').click()
    await page.locator('#sort-select').selectOption('price_asc')

    // The list resets to a single batch rather than appending to the old one.
    await expect.poll(() => cards(page).count(), { timeout: 20_000 }).toBe(12)
    await expect.poll(() => page.url(), { timeout: 10_000 }).toContain('sort=price_asc')
    expect(page.url()).not.toContain('page=')

    const seen = await ids(page)
    expect(new Set(seen).size).toBe(seen.length)

    const prices = await page.locator('.product-card__price').evaluateAll((nodes) =>
      nodes.map((node) => Number(node.textContent.replace(/[^0-9.]/g, ''))),
    )
    expect(prices).toEqual([...prices].sort((a, b) => a - b))

    // The first request after the change must not carry the previous query's cursor.
    const firstAfterChange = requests.find((url) => url.includes('sort=price_asc'))
    expect(firstAfterChange).toBeDefined()
    expect(firstAfterChange).not.toContain('cursor=')
  })

  test('a slow response from the previous query never lands on screen', async ({ page }) => {
    await page.route('**/api/products*', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 700))
      await route.continue()
    })

    await page.goto('/')
    await expect(cards(page).first()).toBeVisible({ timeout: 25_000 })
    const firstIds = await ids(page)

    await page.locator('.filters__toggle').click()
    await page.locator('#sort-select').selectOption('price_asc')

    await expect.poll(() => cards(page).count(), { timeout: 25_000 }).toBe(12)
    await expect.poll(() => ids(page), { timeout: 25_000 }).not.toEqual(firstIds)

    const seen = await ids(page)
    expect(new Set(seen).size, 'stale response interleaved with the fresh one').toBe(seen.length)
    await expect(loadingMore(page)).toBeHidden()

    const prices = await page.locator('.product-card__price').evaluateAll((nodes) =>
      nodes.map((node) => Number(node.textContent.replace(/[^0-9.]/g, ''))),
    )
    expect(prices).toEqual([...prices].sort((a, b) => a - b))
  })

  test('a legacy ?page= link is dropped from the URL', async ({ page }) => {
    await page.goto('/?page=3&category=audio')
    await expect(cards(page).first()).toBeVisible()
    await expect.poll(() => page.url(), { timeout: 10_000 }).not.toContain('page=')
    expect(page.url()).toContain('category=audio')

    const seen = await ids(page)
    expect(new Set(seen).size).toBe(seen.length)
    await expect(page.locator('nav.pagination')).toHaveCount(0)
  })
})
