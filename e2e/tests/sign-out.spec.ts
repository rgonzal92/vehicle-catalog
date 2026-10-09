import { expect } from '@playwright/test';
import { signIn, signOut, test } from './support';

test('signing out returns to the landing page and closes the dashboard', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page).toHaveURL('/dashboard');

  await signOut(page);

  await page.goto('/dashboard');
  await expect(page).toHaveURL('/');
});

test('a person without a role can sign out', async ({ page }) => {
  await signIn(page, 'norole');
  await expect(page).toHaveURL('/no-role');

  await signOut(page);
});
