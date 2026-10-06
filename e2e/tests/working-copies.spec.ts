import { expect, test } from '@playwright/test';
import { expectAccessible, signIn, signOut, startNewCatalog, unique } from './support';

test('an author creates a working copy that starts from the Approved version', async ({ page }) => {
  const name = unique('Winter update');
  await signIn(page, 'author');

  const dialog = await startNewCatalog(page, 'SUV', 'Compact SUV', '2026');
  await expect(dialog.getByText('Starts from Approved v2')).toBeVisible();
  await dialog.getByLabel('Name').fill(name);
  await expectAccessible(page);
  await dialog.getByRole('button', { name: 'Create' }).click();

  // The new working copy opens in the catalog editor, with the contents of its base.
  await expect(page).toHaveURL(/\/catalogs\/\d+$/);
  await expect(page.getByRole('heading', { name })).toBeVisible();
  await expect(page.getByRole('definition')).toHaveText([
    'Compact SUV',
    '2026',
    'Draft',
    'Approved v2',
  ]);
  await expect(page.getByRole('tab', { name: 'Features' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  const matrix = page.locator('app-availability-matrix');
  await expect(matrix.getByRole('columnheader', { name: 'Off-Road' })).toHaveCount(1);
  await expect(matrix.getByRole('row', { name: /ENGINE_20T_I4/ }).getByRole('cell')).toHaveText([
    'ENGINE_20T_I4',
    '-',
    'A',
    'S',
    'S',
    '-',
    '-',
    'A',
  ]);
  await expectAccessible(page);

  await page.getByRole('link', { name: 'Dashboard' }).click();
  const row = page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name });
  await expect(row.getByRole('cell')).toContainText([name, 'Compact SUV', '2026', 'Draft']);
  await expectAccessible(page);
  await row.getByRole('link', { name: `Open ${name}` }).click();
  await expect(page.getByRole('heading', { name })).toBeVisible();
});

test('the dialog names a carryover, and a catalog with nothing to start from starts empty', async ({
  page,
}) => {
  const name = unique('Coupe');
  await signIn(page, 'author');

  let dialog = await startNewCatalog(page, 'Truck', 'Pickup Truck', '2027');
  await expect(dialog.getByText('Starts from 2026 Approved v1 (carryover)')).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel' }).click();

  dialog = await startNewCatalog(page, 'Car', 'Sports Coupe', '2026');
  await expect(dialog.getByText('Starts empty')).toBeVisible();
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByRole('button', { name: 'Create' }).click();

  await expect(page.getByRole('heading', { name })).toBeVisible();
  await expect(page.getByRole('definition')).toHaveText([
    'Sports Coupe',
    '2026',
    'Draft',
    'None (started empty)',
  ]);
  await expectAccessible(page);
});

test('a working copy is created from the Approved view, and only its owner opens it', async ({
  page,
}) => {
  const name = unique('From Approved');
  await signIn(page, 'author');
  await page.getByRole('link', { name: 'Open Pickup Truck 2026' }).click();

  await page.getByRole('button', { name: 'Create working copy' }).click();
  const dialog = page.getByRole('dialog', { name: 'New catalog' });
  await expect(dialog.getByText('Starts from Approved v1')).toBeVisible();
  await expect(dialog.getByLabel('Vehicle line')).toHaveText('Pickup Truck');
  await expect(dialog.getByLabel('Model year')).toHaveText('2026');
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByRole('heading', { name })).toBeVisible();
  const address = page.url();

  // A second one with the same name is refused, whatever its case.
  await page.getByRole('link', { name: 'Dashboard' }).click();
  const second = await startNewCatalog(page, 'Truck', 'Pickup Truck', '2026');
  await second.getByLabel('Name').fill(name.toUpperCase());
  await second.getByRole('button', { name: 'Create' }).click();
  await expect(
    second.getByText('Another of your working copies already has this name.'),
  ).toBeVisible();
  await second.getByRole('button', { name: 'Cancel' }).click();

  // To anyone else, an admin included, it is as if the working copy did not exist.
  await signOut(page);
  await signIn(page, 'admin');
  await page.goto(address);
  await expect(page.getByText('There is no catalog at this address.')).toBeVisible();
});
