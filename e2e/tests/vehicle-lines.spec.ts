import { expect, test } from '@playwright/test';
import { choose, expectAccessible, signIn } from './support';

test('an admin adds, changes, deactivates, and reactivates a vehicle line', async ({ page }) => {
  const unique = Date.now();
  const code = `LINE_${unique}`;
  const name = `Test line ${unique}`;
  const renamed = `Renamed line ${unique}`;

  await signIn(page, 'admin');
  await page.getByRole('link', { name: 'Vehicle lines' }).click();
  await expect(page).toHaveURL('/admin/vehicle-lines');
  await expect(page.getByRole('heading', { name: 'Vehicle lines' })).toBeVisible();

  await page.getByRole('button', { name: 'Add vehicle line' }).click();
  const adding = page.getByRole('dialog', { name: 'Add vehicle line' });
  await adding.getByLabel('Code').fill(code);
  await adding.getByLabel('Name').fill(name);
  await choose(adding, 'Vehicle type', 'SUV');
  await expectAccessible(page);
  await adding.getByRole('button', { name: 'Save' }).click();

  const row = page.getByRole('row', { name: new RegExp(code) });
  await expect(row).toContainText(name);
  await expect(row).toContainText('SUV');
  await expect(row).toContainText('Active');

  await page.getByRole('button', { name: 'Add vehicle line' }).click();
  await adding.getByLabel('Code').fill(code);
  await adding.getByLabel('Name').fill(`Another ${name}`);
  await choose(adding, 'Vehicle type', 'Car');
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding.getByRole('alert')).toContainText('already uses this code or name');
  await adding.getByRole('button', { name: 'Cancel' }).click();

  await row.getByRole('button', { name: `Edit ${name}` }).click();
  const editing = page.getByRole('dialog', { name: 'Edit vehicle line' });
  await editing.getByLabel('Name').fill(renamed);
  await choose(editing, 'Vehicle type', 'Truck');
  await editing.getByRole('button', { name: 'Save' }).click();
  await expect(row).toContainText(renamed);
  await expect(row).toContainText('Truck');

  await row.getByRole('button', { name: `Deactivate ${renamed}` }).click();
  await expect(row).toContainText('Inactive');

  await page.reload();
  await expect(row).toContainText(renamed);
  await expect(row).toContainText('Truck');
  await expect(row).toContainText('Inactive');

  await row.getByRole('button', { name: `Activate ${renamed}` }).click();
  await expect(row).toContainText('Active');
  await expectAccessible(page);
});

test('an author has no way to the vehicle lines screen', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page).toHaveURL('/dashboard');
  await expect(page.getByRole('link', { name: 'Vehicle lines' })).toHaveCount(0);

  await page.goto('/admin/vehicle-lines');
  await expect(page).toHaveURL('/dashboard');
});
