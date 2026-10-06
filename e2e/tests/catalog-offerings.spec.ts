import { expect, test, type Locator, type Page } from '@playwright/test';
import { choose, createWorkingCopy, expectAccessible, signIn, turboOf } from './support';

/** Opens the dialog for the catalog's trims, regions, and offerings from the catalog editor. */
async function manage(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: 'Manage trims and regions' }).click();
  const dialog = page.getByRole('dialog', { name: 'Manage trims and regions' });
  await expect(dialog.getByText('Tick where each trim is sold.')).toBeVisible();

  return dialog;
}

/** The box that says whether the trim is sold in the region. */
const sold = (dialog: Locator, trim: string, region: string) =>
  dialog.getByRole('checkbox', { name: `${trim} is sold in ${region}` });

test('the owner of an empty working copy adds trims and regions and ticks where each trim is sold', async ({
  page,
}) => {
  await signIn(page, 'admin');
  await createWorkingCopy(page, 'Car', 'Sports Coupe', '2026');
  const matrix = page.locator('app-availability-matrix');
  await expect(matrix.getByRole('columnheader', { name: 'Feature' })).toBeVisible();
  await expect(matrix.getByRole('columnheader')).toHaveCount(2);

  const dialog = await manage(page);
  await expect(dialog.getByText('This catalog has no trims yet.')).toBeVisible();
  for (const trim of ['Sport', 'Base']) {
    await choose(dialog, 'Add a trim', trim);
    await expect(dialog.getByRole('rowheader', { name: trim })).toBeVisible();
  }
  for (const region of ['Europe', 'North America']) {
    await choose(dialog, 'Add a region', region);
    await expect(dialog.getByRole('columnheader', { name: new RegExp(region) })).toBeVisible();
  }
  // In the library's order, not the order they were added in.
  await expect(dialog.getByRole('rowheader')).toHaveText(['Base', 'Sport']);
  for (const [trim, region] of [
    ['Base', 'North America'],
    ['Base', 'Europe'],
    ['Sport', 'Europe'],
  ]) {
    await sold(dialog, trim, region).click();
    await expect(sold(dialog, trim, region)).toBeChecked();
  }
  await expect(sold(dialog, 'Sport', 'North America')).not.toBeChecked();
  await expectAccessible(page);
  await dialog.getByRole('button', { name: 'Done' }).click();

  // Regions on top and, under each, only the trims sold there.
  await expect(matrix.getByRole('columnheader', { name: 'North America' })).toHaveAttribute(
    'colspan',
    '1',
  );
  await expect(matrix.getByRole('columnheader', { name: 'Europe' })).toHaveAttribute(
    'colspan',
    '2',
  );
  await expect(matrix.getByRole('columnheader', { name: 'Base', exact: true })).toHaveCount(2);
  await expect(matrix.getByRole('columnheader', { name: 'Sport', exact: true })).toHaveCount(1);

  // Each change is in the history with its kind.
  await page.getByRole('tab', { name: 'History' }).click();
  const history = page.locator('app-history-tab').locator('tbody').getByRole('row');
  await expect(history).toHaveCount(7);
  await expect(history.nth(0).getByRole('cell')).toHaveText([
    /20\d\d/,
    'Demo Admin',
    'Offering added',
    'Sport in Europe',
  ]);
  await expect(history.nth(6).getByRole('cell')).toHaveText([
    /20\d\d/,
    'Demo Admin',
    'Trim added',
    'Sport',
  ]);
});

test('removing an offering, a region, or a trim first says how many cells go with it', async ({
  page,
}) => {
  await signIn(page, 'manager');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const matrix = page.locator('app-availability-matrix');
  await expect(turboOf(page)).toHaveCount(8);

  // Unticking asks first, and keeping leaves everything as it was.
  let dialog = await manage(page);
  await sold(dialog, 'Base', 'Europe').click();
  const question = dialog.getByText(/^Base will no longer be sold in Europe\. \d+ cells go with/);
  await expect(question).toBeVisible();
  await expectAccessible(page);
  await dialog.getByRole('button', { name: 'Keep' }).click();
  await expect(sold(dialog, 'Base', 'Europe')).toBeChecked();

  await sold(dialog, 'Base', 'Europe').click();
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(sold(dialog, 'Base', 'Europe')).not.toBeChecked();
  await expect(sold(dialog, 'Base', 'North America')).toBeChecked();
  await dialog.getByRole('button', { name: 'Done' }).click();
  // The row has lost that offering's cell and no other: Base in Europe was the fifth.
  await expect(turboOf(page)).toHaveText(['ENGINE_20T_I4', '-', 'A', 'S', 'S', '-', 'A']);

  // The region picker hides a region without changing anything.
  const requests: string[] = [];
  page.on('request', (request) => requests.push(request.method()));
  const europeShown = page
    .getByRole('group', { name: 'Regions shown' })
    .getByRole('checkbox', { name: 'Europe' });
  await europeShown.uncheck();
  await expect(matrix.getByRole('columnheader', { name: 'Europe' })).toHaveCount(0);
  await expect(turboOf(page)).toHaveText(['ENGINE_20T_I4', '-', 'A', 'S', 'S']);
  await europeShown.check();
  await expect(turboOf(page)).toHaveText(['ENGINE_20T_I4', '-', 'A', 'S', 'S', '-', 'A']);
  expect(requests).toEqual([]);

  // Removing a region takes its offerings and their cells; removing a trim does the same.
  dialog = await manage(page);
  await dialog.getByRole('button', { name: 'Remove Europe' }).click();
  await expect(
    dialog.getByText(/^Remove Europe from this catalog\? 2 offerings and \d+ cells go with it\./),
  ).toBeVisible();
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(dialog.getByRole('columnheader', { name: /Europe/ })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Remove Sport' }).click();
  await expect(
    dialog.getByText(/^Remove Sport from this catalog\? 1 offering and \d+ cells go with it\./),
  ).toBeVisible();
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(dialog.getByRole('rowheader')).toHaveText(['Base', 'Touring', 'Off-Road']);
  await dialog.getByRole('button', { name: 'Done' }).click();

  await expect(matrix.getByRole('columnheader', { name: 'Europe' })).toHaveCount(0);
  await expect(turboOf(page)).toHaveText(['ENGINE_20T_I4', '-', 'S', 'S']);
  await page.reload();
  await expect(turboOf(page)).toHaveText(['ENGINE_20T_I4', '-', 'S', 'S']);
});

test('a catalog follows the library: a trim renamed there is renamed in the matrix, and one deactivated there stays until removed', async ({
  page,
}) => {
  const added = `Edition ${Date.now()}`;
  const renamed = `Special ${added}`;
  await signIn(page, 'admin');

  // The library gets a new trim.
  await page.getByRole('link', { name: 'Trims' }).click();
  await page.getByRole('button', { name: 'Add trim' }).click();
  await page.getByRole('dialog', { name: 'Add trim' }).getByLabel('Name').fill(added);
  await page
    .getByRole('dialog', { name: 'Add trim' })
    .getByRole('button', { name: 'Save' })
    .click();
  await expect(page.getByRole('row', { name: new RegExp(added) })).toBeVisible();

  // A working copy adds it and sells it in North America.
  await page.getByRole('link', { name: 'Dashboard' }).click();
  await createWorkingCopy(page, 'Car', 'Sports Coupe', '2027');
  const editor = page.url();
  let dialog = await manage(page);
  await choose(dialog, 'Add a trim', added);
  await choose(dialog, 'Add a region', 'North America');
  await sold(dialog, added, 'North America').click();
  await expect(sold(dialog, added, 'North America')).toBeChecked();
  await dialog.getByRole('button', { name: 'Done' }).click();
  const matrix = page.locator('app-availability-matrix');
  await expect(matrix.getByRole('columnheader', { name: added, exact: true })).toBeVisible();

  // Renamed in the library, it is renamed in the working copy.
  await page.goto('/admin/trims');
  await page.getByRole('button', { name: `Edit ${added}` }).click();
  await page.getByRole('dialog', { name: 'Edit trim' }).getByLabel('Name').fill(renamed);
  await page
    .getByRole('dialog', { name: 'Edit trim' })
    .getByRole('button', { name: 'Save' })
    .click();
  await expect(page.getByRole('row', { name: new RegExp(renamed) })).toBeVisible();
  await page.goto(editor);
  await expect(matrix.getByRole('columnheader', { name: renamed, exact: true })).toBeVisible();
  await expect(matrix.getByRole('columnheader', { name: added, exact: true })).toHaveCount(0);

  // Deactivated in the library, it stays in the catalog, marked, until it is removed.
  await page.goto('/admin/trims');
  await page.getByRole('button', { name: `Deactivate ${renamed}` }).click();
  await expect(
    page.getByRole('row', { name: new RegExp(renamed) }).getByText('Inactive', { exact: true }),
  ).toBeVisible();
  await page.goto(editor);
  await expect(matrix.getByRole('columnheader', { name: renamed, exact: true })).toBeVisible();
  dialog = await manage(page);
  await expect(dialog.getByRole('rowheader', { name: new RegExp(renamed) })).toContainText(
    'Inactive',
  );
  await dialog.getByRole('button', { name: `Remove ${renamed}` }).click();
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(dialog.getByText('This catalog has no trims yet.')).toBeVisible();

  // Once removed, it is not on offer.
  await dialog.getByLabel('Add a trim').click();
  await expect(page.getByRole('option', { name: 'Base', exact: true })).toBeVisible();
  await expect(page.getByRole('option', { name: renamed, exact: true })).toHaveCount(0);
});
