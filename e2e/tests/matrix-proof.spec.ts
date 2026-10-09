import { expect, test } from '@playwright/test';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/** The production build the stack serves. */
const built = join(__dirname, '../../frontend/dist/vehicle-catalog/browser');

test('the development pages are absent from the production build', async ({ page }) => {
  const scripts = readdirSync(built).filter((file) => file.endsWith('.js'));
  const holdingAPage = scripts.filter((file) =>
    /app-matrix-proof-page|dev\/matrix|app-looks-page|dev\/looks/.test(
      readFileSync(join(built, file), 'utf8'),
    ),
  );
  expect(scripts.length).toBeGreaterThan(0);
  expect(holdingAPage).toEqual([]);

  for (const address of ['/dev/matrix', '/dev/looks']) {
    await page.goto(address);
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
  }
});
