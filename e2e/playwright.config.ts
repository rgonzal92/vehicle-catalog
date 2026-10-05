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
  workers: process.env.CI ? 1 : undefined,
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
