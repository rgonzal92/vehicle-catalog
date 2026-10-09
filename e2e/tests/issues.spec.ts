import { expect } from '@playwright/test';
import { choose, createWorkingCopy, expectAccessible, nextSave, signIn, test } from './support';

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

test('a cell that breaks a global rule is marked, listed, and shown from the list', async ({
  page,
}) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const matrix = page.locator('app-availability-matrix');
  const rowFilters = page.getByRole('search', { name: 'Feature rows shown' });
  const cellsOf = (code: string) =>
    matrix.getByRole('row', { name: new RegExp(code) }).getByRole('cell');
  const counts = page.locator('[data-issue-counts]');
  await expect(counts).not.toContainText('Error');

  // The Tow Package is Available on Sport in North America, and requires Heavy-Duty Cooling there.
  await rowFilters.getByLabel('Code or name').fill('COOLING_HEAVY_DUTY');
  let saved = nextSave(page);
  await cellsOf('COOLING_HEAVY_DUTY').nth(2).focus();
  await page.keyboard.press('-');
  expect(await saved).toBe(200);
  await expect(counts).toContainText('1 Error');

  await rowFilters.getByLabel('Code or name').fill('PACKAGE_TOW');
  const tow = cellsOf('PACKAGE_TOW').nth(2);
  const says =
    'Tow Package requires Heavy-Duty Cooling, which is not offered on Sport in North America.';
  await expect(tow).toHaveText(new RegExp(`^\\s*A ●Error: ${says}`));
  await expect(tow).toHaveAttribute('title', `Error: ${says}`);
  await expectAccessible(page);

  await page.getByRole('tab', { name: 'Issues' }).click();
  const issue = page.getByRole('tabpanel', { name: 'Issues' }).getByRole('row', { name: says });
  await expect(issue).toContainText('Global rule');
  await expect(issue).toContainText('Tow Package, Sport in North America');
  await issue.getByRole('button', { name: /^Show the cell of this issue/ }).click();
  await expect(page.getByRole('tab', { name: 'Features' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(cellsOf('PACKAGE_TOW').nth(2)).toBeFocused();

  // Putting the cell right clears the issue.
  await rowFilters.getByLabel('Code or name').fill('COOLING_HEAVY_DUTY');
  saved = nextSave(page);
  await cellsOf('COOLING_HEAVY_DUTY').nth(2).focus();
  await page.keyboard.press('a');
  expect(await saved).toBe(200);
  await expect(counts).not.toContainText('Error');
});

test('the dashboard counts the issues of each working copy', async ({ page }) => {
  await signIn(page, 'admin');
  const name = await createWorkingCopy(page, 'Car', 'Sports Coupe', '2027');
  await expect(page.locator('[data-issue-counts]')).toHaveText('3 Errors');

  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Dashboard' })
    .click();

  const listed = page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name });
  await expect(listed).toContainText('3 Errors');
  await expectAccessible(page);
});
