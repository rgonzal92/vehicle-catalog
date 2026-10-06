import { expect, test } from '@playwright/test';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/** The production build the stack serves. */
const built = join(__dirname, '../../frontend/dist/vehicle-catalog/browser');

test('the matrix proof page is absent from the production build', async ({ page }) => {
  const scripts = readdirSync(built).filter((file) => file.endsWith('.js'));
  const holdingThePage = scripts.filter((file) =>
    /app-matrix-proof-page|dev\/matrix/.test(readFileSync(join(built, file), 'utf8')),
  );
  expect(scripts.length).toBeGreaterThan(0);
  expect(holdingThePage).toEqual([]);

  await page.goto('/dev/matrix');
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
});
