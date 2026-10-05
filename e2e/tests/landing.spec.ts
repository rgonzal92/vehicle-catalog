import { expect, test } from '@playwright/test';
import { expectAccessible } from './support';

test('the landing page offers sign-in and lists the demo accounts', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Vehicle Catalog' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveText([/author/, /manager/, /admin/]);
});

test('the landing page is accessible', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('tbody tr')).toHaveCount(3);

  await expectAccessible(page);
});
