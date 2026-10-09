import { defineConfig, devices } from '@playwright/test';

/**
 * Browser tests against the full local stack: Caddy serving the built frontend and proxying /api
 * to the backend, with PostgreSQL and the mock login server behind it. `npm run e2e` builds the
 * frontend, starts the stack, runs the tests, and stops the stack.
 *
 * It needs a `.env` at the repository root with the database password, `npm ci` run in
 * `frontend/` and here, and the browser installed with `npx playwright install chromium`.
 */
/** The part of the suite to run, as "1/2", where the pipeline runs it in parts. */
const [current, total] = (process.env.SHARD ?? '').split('/').map(Number);

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  shard: total ? { current, total } : undefined,
  // A test keeps to catalogs of its own, so the tests run side by side: as many at a time as half
  // the machine's processors, which is Playwright's own choice and comes to two in the pipeline.
  // Four at a time there left the browsers short of processor time, and tests failed for it.
  // Side by side a test takes longer than it does alone, so it has sixty seconds, not thirty.
  timeout: 60_000,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:8092',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
