import { expect } from '@playwright/test';
import { createWorkingCopy, expectAccessible, signIn, test } from './support';

test('the dashboard stands in for a list that is slow, and says so when one cannot be read', async ({
  page,
}) => {
  let answer: 'held' | 'refused' | 'given' = 'held';
  let release = () => {};
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route('**/api/catalogs?scope=mine', async (route) => {
    if (answer === 'refused') {
      return route.fulfill({ status: 500, json: {} });
    }
    if (answer === 'held') {
      await held;
    }
    return route.continue();
  });
  await signIn(page, 'author');
  const mine = page.getByRole('region', { name: 'My catalogs' });

  await expect(mine.locator('app-loading')).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expectAccessible(page);
  release();
  await expect(mine.locator('app-loading')).toHaveCount(0);

  answer = 'refused';
  await page.reload();
  await expect(mine.getByRole('alert')).toContainText('Your catalogs could not be read.');
  await expectAccessible(page);

  answer = 'given';
  await mine.getByRole('button', { name: 'Try again' }).click();
  await expect(mine.getByRole('alert')).toHaveCount(0);
  await expect(mine.locator('app-loading')).toHaveCount(0);
});

test('a dialog is no wider than a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 800 });
  await signIn(page, 'admin');

  /** Checks the open dialog of that name against the screen's edges, then closes it. */
  const fits = async (name: string) => {
    const dialog = page.getByRole('dialog', { name });
    await expect(async () => {
      const box = (await dialog.boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(400);
    }).toPass();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
  };

  await page.getByRole('button', { name: 'New catalog' }).click();
  await fits('New catalog');

  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  await page.getByRole('button', { name: 'Add features' }).click();
  await fits('Add features');
  await page.getByRole('button', { name: 'Manage trims and regions' }).click();
  await fits('Manage trims and regions');

  await page.goto('/admin/features');
  await page.getByRole('button', { name: 'Add feature' }).click();
  await fits('Add feature');
});
