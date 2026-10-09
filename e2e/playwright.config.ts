import { defineConfig, devices } from '@playwright/test';

/**
 * Browser tests against the full local stack: Caddy serving the built frontend and proxying /api
 * to the backend, with PostgreSQL and the mock login server behind it. `npm run e2e` builds the
 * frontend, starts the stack, runs the tests, and stops the stack.
 *
 * It needs a `.env` at the repository root with the database password, `npm ci` run in
 * `frontend/` and here, and the browser installed with `npx playwright install chromium`.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // The pipeline's machine has four processors. A test keeps to catalogs of its own, so the
  // tests run side by side there as they do on a developer's machine.
  workers: process.env.CI ? 4 : undefined,
  // Four at a time on that machine, a test takes about 1.7 times as long as it does alone, and
  // the longest came within a second or two of the thirty a test has unless it is given more.
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
