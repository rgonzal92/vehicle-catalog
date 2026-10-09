import { expect, type Locator, type Page } from '@playwright/test';
import { choose, createWorkingCopy, expectAccessible, signIn, test } from './support';

/** Opens the feature picker from the catalog editor. */
async function pick(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: 'Add features' }).click();
  const dialog = page.getByRole('dialog', { name: 'Add features' });
  await expect(dialog.getByRole('row').nth(1)).toBeVisible();

  return dialog;
}

/** Searches the picker by part of a code or a name. */
async function search(dialog: Locator, text: string): Promise<void> {
  await dialog.getByLabel('Code or name').fill(text);
  await dialog.getByRole('button', { name: 'Search' }).click();
}

test('the owner adds feature rows from the library, narrows the matrix to them, and removes one', async ({
  page,
}) => {
  await signIn(page, 'manager');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const matrix = page.locator('app-availability-matrix');
  const shown = page.locator('[data-rows-shown]');
  await expect(shown).toHaveText('151 of 151 feature rows shown');

  // The picker searches by code or name and filters by category and kind. What is ticked stays
  // ticked from one search to the next, and a feature that is a row already cannot be ticked.
  const dialog = await pick(page);
  await search(dialog, 'removable');
  await expect(dialog.locator('tbody').getByRole('row')).toHaveCount(1);
  await dialog.getByRole('checkbox', { name: 'Add Removable Roof' }).check();
  await search(dialog, 'tow');
  await choose(dialog, 'Category', 'Packages');
  await choose(dialog, 'Kind', 'Package');
  await expect(dialog.getByRole('cell', { name: 'Exterior' })).toHaveCount(0);
  await dialog.getByRole('checkbox', { name: 'Add Heavy-Duty Tow Package' }).check();
  await expect(dialog.getByText('2 features ticked')).toBeVisible();
  await search(dialog, 'ROOF_PANORAMIC');
  await choose(dialog, 'Category', 'Every category');
  await choose(dialog, 'Kind', 'Every kind');
  await expect(
    dialog.getByRole('checkbox', { name: 'Panoramic Roof is already a feature row' }),
  ).toBeDisabled();
  await expect(dialog.getByText('(already a row)')).toBeVisible();
  await expectAccessible(page);
  await dialog.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(shown).toHaveText('153 of 153 feature rows shown');

  // The matrix is narrowed to the rows being worked on. Each new row has every cell Not offered.
  const rowFilters = page.getByRole('search', { name: 'Feature rows shown' });
  const requests: string[] = [];
  page.on('request', (request) => requests.push(request.method()));
  await rowFilters.getByLabel('Code or name').fill('roof_rem');
  await expect(shown).toHaveText('1 of 153 feature rows shown');
  await expect(matrix.getByRole('row', { name: /ROOF_REMOVABLE/ }).getByRole('cell')).toHaveText([
    'ROOF_REMOVABLE',
    '-',
    '-',
    '-',
    '-',
    '-',
    '-',
    '-',
  ]);
  // It stands under its category's subheader, and no other category is shown.
  await expect(matrix.locator('tbody').getByRole('row')).toHaveCount(2);
  await expect(matrix.locator('tbody').getByRole('row').first()).toHaveText('Exterior');
  await rowFilters.getByLabel('Code or name').fill('');
  await choose(rowFilters, 'Kind', 'Package');
  await expect(shown).toHaveText('6 of 153 feature rows shown');
  await expect(matrix.getByRole('row', { name: /PACKAGE_TOW_HEAVY_DUTY/ })).toBeVisible();
  await choose(rowFilters, 'Category', 'Exterior');
  await expect(shown).toHaveText('0 of 153 feature rows shown');
  await choose(rowFilters, 'Kind', 'Every kind');
  await rowFilters.getByLabel('Code or name').fill('removable');
  await expect(shown).toHaveText('1 of 153 feature rows shown');
  expect(requests).toEqual([]);
  await expectAccessible(page);

  // Removing a row asks first, and takes the row's cells with it.
  await matrix
    .getByRole('row', { name: /ROOF_REMOVABLE/ })
    .getByRole('cell')
    .nth(1)
    .focus();
  await page.keyboard.press('s');
  await page.keyboard.press('ArrowLeft');
  await expect(page.getByRole('button', { name: 'Remove Removable Roof' })).toBeFocused();
  await page.keyboard.press('Enter');
  const question = page.getByRole('dialog', { name: 'Remove feature row' });
  await expect(question).toContainText(
    'Remove Removable Roof (ROOF_REMOVABLE) from this catalog? 1 cell goes with it.',
  );
  await expectAccessible(page);
  await question.getByRole('button', { name: 'Keep' }).click();
  await expect(matrix.getByRole('row', { name: /ROOF_REMOVABLE/ })).toBeVisible();
  // The focus is back on the button that asked, so the keyboard goes on from there.
  await expect(page.getByRole('button', { name: 'Remove Removable Roof' })).toBeFocused();
  await page.keyboard.press('Enter');
  await question.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(question).toBeHidden();
  await expect(shown).toHaveText('0 of 152 feature rows shown');

  // Each change is in the history with its kind.
  await page.getByRole('tab', { name: 'History' }).click();
  const history = page.locator('app-history-tab').locator('tbody').getByRole('row');
  await expect(history.nth(0).getByRole('cell')).toHaveText([
    /20\d\d/,
    'Demo Manager',
    'Feature removed',
    'Removable Roof (ROOF_REMOVABLE)',
  ]);
  await expect(history.nth(3).getByRole('cell')).toHaveText([
    /20\d\d/,
    'Demo Manager',
    'Feature added',
    'Removable Roof (ROOF_REMOVABLE)',
  ]);
});

test('a retired feature is not listed in the picker', async ({ page }) => {
  const unique = Date.now();
  const code = `RETIRING_${unique}`;
  const name = `Retiring feature ${unique}`;
  await signIn(page, 'admin');

  // The library gets a feature, which a catalog can add while it is active.
  await page.getByRole('link', { name: 'Feature library' }).click();
  await page.getByRole('button', { name: 'Add feature' }).click();
  const adding = page.getByRole('dialog', { name: 'Add feature' });
  await adding.getByLabel('Code').fill(code);
  await adding.getByLabel('Name').fill(name);
  await choose(adding, 'Category', 'Exterior');
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding).toBeHidden();

  await page.getByRole('link', { name: 'Dashboard' }).click();
  await createWorkingCopy(page, 'Car', 'Sports Coupe', '2028');
  const editor = page.url();
  let dialog = await pick(page);
  await search(dialog, code);
  await expect(dialog.getByRole('checkbox', { name: `Add ${name}` })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Cancel' }).click();

  // Retired, it is no longer listed.
  await page.goto('/admin/features');
  const filters = page.getByRole('search');
  await filters.getByLabel('Code or name').fill(code);
  await filters.getByRole('button', { name: 'Search' }).click();
  await page.getByRole('button', { name: `Retire ${name}` }).click();
  await expect(page.getByRole('row', { name: new RegExp(code) })).toContainText('Retired');
  await page.goto(editor);
  dialog = await pick(page);
  await search(dialog, code);
  await expect(dialog.getByText('No active features match.')).toBeVisible();
});
