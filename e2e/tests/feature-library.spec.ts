import { expect } from '@playwright/test';
import { choose, expectAccessible, signIn, test } from './support';

test('an admin adds, edits, retires, and reactivates features and finds them by search', async ({
  page,
}) => {
  const unique = Date.now();
  const code = `FEATURE_${unique}`;
  const packageCode = `PACKAGE_${unique}`;
  const name = `Test feature ${unique}`;
  const renamed = `Renamed feature ${unique}`;

  await signIn(page, 'admin');
  await page.getByRole('link', { name: 'Feature library' }).click();
  await expect(page).toHaveURL('/admin/features');
  await expect(page.getByRole('heading', { name: 'Feature library' })).toBeVisible();

  const filters = page.getByRole('search');
  const search = async (text: string) => {
    await filters.getByLabel('Code or name').fill(text);
    await filters.getByRole('button', { name: 'Search' }).click();
  };

  await page.getByRole('button', { name: 'Add feature' }).click();
  const adding = page.getByRole('dialog', { name: 'Add feature' });
  await adding.getByLabel('Code').fill('not a code');
  await adding.getByLabel('Name').fill(name);
  await adding.getByLabel('Description').fill('Made by a browser test.');
  await choose(adding, 'Category', 'Exterior');
  await expectAccessible(page);
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding.getByRole('alert')).toContainText('Use 2 to 40 capital letters');

  await adding.getByLabel('Code').fill(code);
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding).toBeHidden();

  await page.getByRole('button', { name: 'Add feature' }).click();
  await adding.getByLabel('Code').fill(code);
  await adding.getByLabel('Name').fill(`Another ${name}`);
  await choose(adding, 'Category', 'Interior');
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding.getByRole('alert')).toContainText('already uses this code');

  // A package has no category to choose: it is always in Packages.
  await adding.getByLabel('Code').fill(packageCode);
  await choose(adding, 'Kind', 'Package');
  await expect(adding.getByLabel('Category')).toHaveCount(0);
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding).toBeHidden();

  await search(String(unique));
  const row = page.getByRole('row', { name: new RegExp(code) });
  const packageRow = page.getByRole('row', { name: new RegExp(packageCode) });
  await expect(row).toContainText(name);
  await expect(row).toContainText('Made by a browser test.');
  await expect(row).toContainText('Exterior');
  await expect(row).toContainText('Active');
  await expect(packageRow).toContainText('Packages');

  await row.getByRole('button', { name: `Edit ${name}` }).click();
  const editing = page.getByRole('dialog', { name: 'Edit feature' });
  await expect(editing.getByLabel('Code')).toHaveCount(0);
  await expect(editing.getByLabel('Kind')).toHaveCount(0);
  await editing.getByLabel('Name').fill(renamed);
  await editing.getByLabel('Description').fill('Edited by a browser test.');
  await choose(editing, 'Category', 'Interior');
  await editing.getByRole('button', { name: 'Save' }).click();
  await expect(row).toContainText(renamed);
  await expect(row).toContainText('Edited by a browser test.');
  await expect(row).toContainText('Interior');

  await choose(filters, 'Kind', 'Package');
  await expect(packageRow).toBeVisible();
  await expect(row).toHaveCount(0);
  await choose(filters, 'Kind', 'Every kind');
  await choose(filters, 'Category', 'Interior');
  await expect(row).toBeVisible();
  await expect(packageRow).toHaveCount(0);
  await choose(filters, 'Category', 'Every category');

  await row.getByRole('button', { name: `Retire ${renamed}` }).click();
  await expect(row).toContainText('Retired');
  await choose(filters, 'Status', 'Retired');
  await expect(row).toBeVisible();
  await expect(packageRow).toHaveCount(0);

  await row.getByRole('button', { name: `Reactivate ${renamed}` }).click();
  await expect(row).toHaveCount(0);
  await choose(filters, 'Status', 'Every status');
  await expect(row).toContainText('Active');
  await expectAccessible(page);
});

test('an author has no way to the feature library screen', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page).toHaveURL('/dashboard');
  await expect(page.getByRole('link', { name: 'Feature library' })).toHaveCount(0);

  await page.goto('/admin/features');
  await expect(page).toHaveURL('/dashboard');
});
