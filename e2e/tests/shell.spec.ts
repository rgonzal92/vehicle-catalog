import { expect, test } from '@playwright/test';
import { expectAccessible, signIn } from './support';

test('the frame leads to each page, and says which one is open', async ({ page }) => {
  await signIn(page, 'admin');
  const pages = page.getByRole('navigation', { name: 'Main' });
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Dashboard');

  await pages.getByRole('link', { name: 'Feature library' }).click();

  await expect(page).toHaveURL('/admin/features');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Feature library');
  await expect(page.getByRole('heading', { level: 1 })).toBeFocused();
  await expect(pages.getByRole('link', { name: 'Feature library' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expectAccessible(page);
});

test('the switch between light and dark is remembered', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page.locator('html')).not.toHaveClass(/app-dark/);

  await page.getByRole('button', { name: 'Use dark colors' }).click();
  await expect(page.locator('html')).toHaveClass(/app-dark/);

  await page.reload();
  await expect(page.locator('html')).toHaveClass(/app-dark/);
  await expect(page.getByRole('button', { name: 'Use light colors' })).toBeVisible();
});

test('a collapsed sidebar is remembered and still leads to each page', async ({ page }) => {
  await signIn(page, 'admin');

  await page.getByRole('button', { name: 'Collapse the sidebar' }).click();
  await expect(page.getByRole('button', { name: 'Expand the sidebar' })).toBeVisible();
  await expectAccessible(page);

  await page.reload();
  await expect(page.getByRole('button', { name: 'Expand the sidebar' })).toBeVisible();
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Users' }).click();
  await expect(page).toHaveURL('/admin/users');
});

test('the first stop of the Tab key skips to the page', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Dashboard');

  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.keyboard.press('Enter');

  await expect(page.getByRole('main')).toBeFocused();
  await expect(page).toHaveURL('/dashboard');
});
