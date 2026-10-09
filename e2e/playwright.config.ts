import { defineConfig, devices } from '@playwright/test';
import { readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Browser tests against the full local stack: Caddy serving the built frontend and proxying /api
 * to the backend, with PostgreSQL and the mock login server behind it. `npm run e2e` builds the
 * frontend, starts the stack, runs the tests, and stops the stack.
 *
 * It needs a `.env` at the repository root with the database password, `npm ci` run in
 * `frontend/` and here, and the browser installed with `npx playwright install chromium`.
 */
/**
 * The parts the pipeline runs the suite in, each on a machine of its own, as the spec files of
 * each. They are shared out by how long they take and not by how many they are, so that the parts
 * take about the same time: the check a merge waits for is as slow as its slowest part. A spec
 * file that is added goes to the part that takes less time.
 */
const parts = [
  [
    'approval',
    'feature-rows',
    'needs-revision',
    'submit',
    'issues',
    'save-failures',
    'page-states',
    'vehicle-lines',
    'export',
    'history',
    'roles',
    'sign-out',
    'seeded-library',
    'landing',
    'seeded-catalogs',
  ],
  [
    'catalog-rules',
    'catalog-offerings',
    'working-copies',
    'review-queue',
    'global-rules',
    'feature-library',
    'trims-and-regions',
    'rename-and-delete',
    'cell-edits',
    'users',
    'approved-view',
    'jobs',
    'shell',
    'matrix-proof',
    'trial',
  ],
];

/**
 * The spec files of one part, named as "1/2" for the first of two. Every spec file has to be in
 * exactly one part, or nothing is run: one that is added is not left out by being forgotten.
 */
function specsOf(part: string): string[] {
  const [current, total] = part.split('/').map(Number);
  const files = readdirSync(join(__dirname, 'tests'))
    .filter((file) => file.endsWith('.spec.ts'))
    .map((file) => file.slice(0, -'.spec.ts'.length));
  const shared = parts.flat();
  const astray = [
    ...files.filter((file) => shared.filter((name) => name === file).length !== 1),
    ...shared.filter((name) => !files.includes(name)),
  ];
  if (total !== parts.length || !parts[current - 1] || astray.length > 0) {
    throw new Error(
      `playwright.config.ts shares the spec files out among ${parts.length} parts, and each has ` +
        `to be in exactly one. Asked for part ${part}. Not in exactly one part, or not a spec ` +
        `file: ${astray.join(', ') || 'none'}.`,
    );
  }

  return parts[current - 1].map((name) => `**/${name}.spec.ts`);
}

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  testMatch: process.env.PART ? specsOf(process.env.PART) : undefined,
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
