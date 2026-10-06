import { expect, test, type Page } from '@playwright/test';
import { expectAccessible } from './support';

/** The rows of the demo accounts table. */
const demoAccountRows = (page: Page) =>
  page.getByRole('region', { name: 'Demo accounts' }).locator('tbody tr');

test('the landing page offers sign-in and lists the demo accounts', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Vehicle Catalog' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
  await expect(demoAccountRows(page)).toHaveText([/author/, /manager/, /admin/]);
  await expect(page.getByRole('region', { name: 'Demo accounts' })).toContainText(
    'Everything visitors do here is deleted every day at 03:00 UTC',
  );
});

test('the landing page is accessible', async ({ page }) => {
  await page.goto('/');
  await expect(demoAccountRows(page)).toHaveCount(3);

  await expectAccessible(page);
});
