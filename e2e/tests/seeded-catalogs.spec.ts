import { expect } from '@playwright/test';
import { signIn, test } from './support';

test('the dashboard lists the seeded lineages with their current Approved versions', async ({
  page,
}) => {
  await signIn(page, 'author');
  const approved = page.getByRole('region', { name: 'Approved catalogs' });

  for (const [vehicleLine, modelYear, version] of [
    ['Compact SUV', '2026', '2'],
    ['Compact SUV', '2027', '1'],
    ['Pickup Truck', '2026', '1'],
    ['Sedan', '2027', '1'],
  ]) {
    const row = approved
      .getByRole('row', { name: new RegExp(vehicleLine) })
      .filter({ has: page.getByRole('cell', { name: modelYear, exact: true }) });
    await expect(row.getByRole('cell')).toHaveText([
      vehicleLine,
      modelYear,
      version,
      /20\d\d/,
      'Demo Manager',
    ]);
  }
});

test('a seeded pickup is sold in two regions, and not every trim in both', async ({ page }) => {
  await signIn(page, 'author');
  await page.getByRole('link', { name: 'Open Pickup Truck 2026', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Pickup Truck 2026' })).toBeVisible();

  const matrix = page.locator('app-availability-matrix');
  await expect(matrix.getByRole('columnheader', { name: 'North America' })).toBeVisible();
  await expect(matrix.getByRole('columnheader', { name: 'South America' })).toBeVisible();
  await expect(matrix.getByRole('columnheader', { name: 'Off-Road' })).toHaveCount(2);
  await expect(matrix.getByRole('columnheader', { name: 'Luxury' })).toHaveCount(1);
});
