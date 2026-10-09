import { expect, test } from '@playwright/test';
import { choose, createWorkingCopy, expectAccessible, signIn } from './support';

test('an empty working copy says what it lacks, and an edit brings its issues up to date', async ({
  page,
}) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'Car', 'Sports Coupe', '2026');
  const counts = page.locator('[data-issue-counts]');
  await expect(counts).toHaveText('3 Errors');

  await page.getByRole('tab', { name: 'Issues' }).click();
  const issues = page.getByRole('tabpanel', { name: 'Issues' });
  await expect(issues.getByRole('row', { name: /The catalog has no trims\./ })).toBeVisible();
  await expect(issues.getByRole('row', { name: /The catalog has no regions\./ })).toBeVisible();
  await expect(
    issues.getByRole('row', { name: /The catalog has no feature rows\./ }),
  ).toBeVisible();
  await expectAccessible(page);

  await page.getByRole('tab', { name: 'Features' }).click();
  await page.getByRole('button', { name: 'Manage trims and regions' }).click();
  const dialog = page.getByRole('dialog', { name: 'Manage trims and regions' });
  await choose(dialog, 'Add a trim', 'Base');
  await expect(dialog.getByRole('rowheader', { name: 'Base' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();

  await page.getByRole('tab', { name: 'Issues' }).click();
  await expect(issues.getByRole('row', { name: /Base is sold in no region\./ })).toBeVisible();
  await expect(issues.getByRole('row', { name: /The catalog has no trims\./ })).toHaveCount(0);
  await expect(counts).toHaveText('3 Errors');
});

test('a working copy of a seeded catalog has no Error', async ({ page }) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');

  await expect(page.locator('[data-issue-counts]')).not.toContainText('Error');
});
