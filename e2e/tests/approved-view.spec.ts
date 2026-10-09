import { expect, test, type Page } from '@playwright/test';
import { expectAccessible, signIn } from './support';

/** Opens the seeded lineage from the dashboard's list of Approved catalogs. */
async function openCompactSuv2026(page: Page): Promise<void> {
  // A working copy of that lineage has a link that begins the same way, so the name is matched whole.
  await page.getByRole('link', { name: 'Open Compact SUV 2026', exact: true }).click();
  await expect(page).toHaveURL(/\/approved\/\d+$/);
  await expect(page.getByRole('heading', { name: 'Compact SUV 2026' })).toBeVisible();
}

test('an author opens an Approved catalog from the dashboard and reads its versions', async ({
  page,
}) => {
  await signIn(page, 'author');
  const row = page
    .getByRole('region', { name: 'Approved catalogs' })
    .getByRole('row', { name: /Compact SUV/ })
    .filter({ has: page.getByRole('cell', { name: '2026', exact: true }) });
  await expect(row.getByRole('cell')).toHaveText([
    'Compact SUV',
    '2026',
    '2',
    /2025/,
    'Demo Manager',
  ]);
  await expectAccessible(page);

  await openCompactSuv2026(page);
  await expect(page.getByText('Approved version 2')).toBeVisible();

  // Regions on top and, under each, only the trims sold there: Off-Road is not sold in Europe.
  const matrix = page.locator('app-availability-matrix');
  await expect(matrix.getByRole('columnheader', { name: 'North America' })).toBeVisible();
  await expect(matrix.getByRole('columnheader', { name: 'Europe' })).toBeVisible();
  await expect(matrix.getByRole('columnheader', { name: 'Base', exact: true })).toHaveCount(2);
  await expect(matrix.getByRole('columnheader', { name: 'Off-Road' })).toHaveCount(1);

  // The same feature on the same trim differs between the regions: Sport has the 2.0L turbo
  // Available in North America and Not offered in Europe.
  const turbo = matrix.getByRole('row', { name: /ENGINE_20T_I4/ });
  await expect(turbo.getByRole('rowheader')).toHaveText('2.0L Turbo I4 Engine');
  await expect(turbo.getByRole('cell')).toHaveText([
    'ENGINE_20T_I4',
    '-',
    'A',
    'S',
    'S',
    '-',
    '-',
    'A',
  ]);

  // The matrix is read-only: a click opens no dropdown.
  await turbo.getByRole('cell').nth(2).click();
  await expect(matrix.getByRole('combobox')).toHaveCount(0);

  // The version is checked against the library as it is today, and its issues are listed.
  const issues = page.getByRole('region', { name: 'Issues' });
  await expect(issues.locator('[data-issue-counts]')).toContainText(/\d+ Warnings?/);
  await expect(issues.locator('[data-issue-counts]')).not.toContainText('Error');
  await expectAccessible(page);

  const versions = page.getByRole('region', { name: 'Versions' });
  await expect(versions.getByRole('row', { name: /Hybrid and autumn update/ })).toContainText(
    'Shown below',
  );
  await expect(versions.getByRole('row', { name: /Launch content/ })).toContainText('Demo Manager');
  await expect(matrix.getByRole('row', { name: /POWERTRAIN_HYBRID/ })).toHaveCount(1);

  await versions.getByRole('button', { name: 'Show version 1' }).click();
  await expect(page.getByText('Approved version 1')).toBeVisible();
  await expect(versions.getByRole('row', { name: /Launch content/ })).toContainText('Shown below');
  await expect(matrix.getByRole('row', { name: /ENGINE_20T_I4/ })).toBeVisible();
  await expect(matrix.getByRole('row', { name: /POWERTRAIN_HYBRID/ })).toHaveCount(0);

  // The cell of each issue is marked in the matrix, and an issue shows its cell. A read-only
  // matrix brings the cell into view and leaves the focus where it is.
  await issues
    .getByRole('button', { name: /^Show the cell of this issue/ })
    .first()
    .click();
  await expect(matrix.locator('td[title^="Warning: "]').first()).toBeInViewport();
});

for (const account of ['manager', 'admin']) {
  test(`the ${account} can open an Approved catalog too`, async ({ page }) => {
    await signIn(page, account);
    await openCompactSuv2026(page);
    await expect(page.locator('app-availability-matrix').getByRole('row').first()).toBeVisible();
  });
}

test('two Approved versions are compared, from the earlier to the later', async ({ page }) => {
  await signIn(page, 'author');
  await openCompactSuv2026(page);

  await page
    .getByRole('region', { name: 'Versions' })
    .getByRole('button', { name: 'Compare version 1 with version 2' })
    .click();

  const comparison = page.getByRole('region', { name: 'Changes from version 1 to version 2' });
  await expect(
    comparison.getByRole('heading', { name: 'Changes from version 1 to version 2' }),
  ).toBeFocused();
  await expect(
    comparison.getByRole('row', {
      name: /^Added Feature row Hybrid Powertrain \(POWERTRAIN_HYBRID\)$/,
    }),
  ).toBeVisible();
  await expect(
    comparison.getByRole('row', {
      name: /^Added Hybrid Powertrain requires Regenerative Braking$/,
    }),
  ).toBeVisible();
  // A changed cell says its availability before and after in words.
  await expect(
    comparison.getByRole('region', { name: 'Cells' }).getByRole('row').nth(1),
  ).toContainText(/(Standard|Available|Not offered).*(Standard|Available|Not offered)/);
  await expectAccessible(page);

  await comparison.getByRole('button', { name: 'Close the comparison' }).click();
  await expect(comparison).toHaveCount(0);
});

test('an address that names no lineage says so', async ({ page }) => {
  await signIn(page, 'author');
  await page.goto('/approved/987654321');

  await expect(page.getByText('There is no Approved version at this address.')).toBeVisible();
});
