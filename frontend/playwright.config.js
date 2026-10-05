import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end checks for the storefront's cursor-driven infinite scroll.
 *
 * These are verification only: the tests drive the real UI against the real API
 * (no mocked product responses, no Playwright-driven data inserts). Point
 * PLAYWRIGHT_BASE_URL at another deployment to run them elsewhere.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Reuses the running dev server (docker compose keeps one on 5173) and only
  // starts one when nothing is listening.
  webServer: {
    command: 'npm run dev -- --host 0.0.0.0 --port 5173',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
