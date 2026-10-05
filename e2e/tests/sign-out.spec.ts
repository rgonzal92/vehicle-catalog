import { expect, test } from '@playwright/test';
import { signIn } from './support';

test('signing out returns to the landing page and closes the dashboard', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page).toHaveURL('/dashboard');

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();

  await page.goto('/dashboard');
  await expect(page).toHaveURL('/');
});

test('a person without a role can sign out', async ({ page }) => {
  await signIn(page, 'norole');
  await expect(page).toHaveURL('/no-role');

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
});
