import { expect, test, type Page } from '@playwright/test';
import { expectAccessible, signIn } from './support';

/** The names in the list's rows, top to bottom, limited to the ones this test made. */
async function namesInOrder(page: Page, mine: string[]): Promise<string[]> {
  const rows = await page.locator('tbody tr').allTextContents();

  return rows.flatMap((row) => mine.filter((name) => row.includes(name)));
}

test('an admin adds, renames, moves, deactivates, and reactivates a trim', async ({ page }) => {
  const unique = Date.now();
  const first = `First trim ${unique}`;
  const second = `Second trim ${unique}`;
  const renamed = `Renamed trim ${unique}`;

  await signIn(page, 'admin');
  await page.getByRole('link', { name: 'Trims' }).click();
  await expect(page).toHaveURL('/admin/trims');
  await expect(page.getByRole('heading', { name: 'Trims' })).toBeVisible();

  const adding = page.getByRole('dialog', { name: 'Add trim' });
  for (const name of [first, second]) {
    await page.getByRole('button', { name: 'Add trim' }).click();
    await adding.getByLabel('Name').fill(name);
    await adding.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('row', { name: new RegExp(name) })).toBeVisible();
  }
  expect(await namesInOrder(page, [first, second])).toEqual([first, second]);

  await page.getByRole('button', { name: 'Add trim' }).click();
  await adding.getByLabel('Name').fill(first.toUpperCase());
  await expectAccessible(page);
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding.getByRole('alert')).toContainText('already uses this name');
  await adding.getByRole('button', { name: 'Cancel' }).click();

  await page.getByRole('button', { name: `Move ${second} up` }).click();
  await expect.poll(() => namesInOrder(page, [first, second])).toEqual([second, first]);

  await page.getByRole('button', { name: `Edit ${first}` }).click();
  const editing = page.getByRole('dialog', { name: 'Edit trim' });
  await editing.getByLabel('Name').fill(renamed);
  await editing.getByRole('button', { name: 'Save' }).click();
  const row = page.getByRole('row', { name: new RegExp(renamed) });
  await expect(row).toBeVisible();

  await page.getByRole('button', { name: `Deactivate ${renamed}` }).click();
  await expect(row.getByText('Inactive', { exact: true })).toBeVisible();

  await page.reload();
  await expect(row.getByText('Inactive', { exact: true })).toBeVisible();
  expect(await namesInOrder(page, [renamed, second])).toEqual([second, renamed]);

  await page.getByRole('button', { name: `Activate ${renamed}` }).click();
  await expect(row.getByText('Active', { exact: true })).toBeVisible();
  await page.reload();
  await expect(row.getByText('Active', { exact: true })).toBeVisible();
  await expectAccessible(page);
});

test('an admin adds, renames, moves, deactivates, and reactivates a region', async ({ page }) => {
  const unique = Date.now();
  const firstCode = `A${unique}`;
  const secondCode = `B${unique}`;
  const first = `First region ${unique}`;
  const second = `Second region ${unique}`;
  const renamed = `Renamed region ${unique}`;

  await signIn(page, 'admin');
  await page.getByRole('link', { name: 'Regions' }).click();
  await expect(page).toHaveURL('/admin/regions');
  await expect(page.getByRole('heading', { name: 'Regions' })).toBeVisible();

  const adding = page.getByRole('dialog', { name: 'Add region' });
  for (const [code, name] of [
    [firstCode, first],
    [secondCode, second],
  ]) {
    await page.getByRole('button', { name: 'Add region' }).click();
    await adding.getByLabel('Code').fill(code);
    await adding.getByLabel('Name').fill(name);
    await adding.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('row', { name: new RegExp(code) })).toContainText(name);
  }

  await page.getByRole('button', { name: 'Add region' }).click();
  await adding.getByLabel('Code').fill(firstCode);
  await adding.getByLabel('Name').fill(`Another ${first}`);
  await expectAccessible(page);
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding.getByRole('alert')).toContainText('already uses this code or name');
  await adding.getByRole('button', { name: 'Cancel' }).click();

  await page.getByRole('button', { name: `Move ${second} up` }).click();
  await expect.poll(() => namesInOrder(page, [first, second])).toEqual([second, first]);

  await page.getByRole('button', { name: `Edit ${first}` }).click();
  const editing = page.getByRole('dialog', { name: 'Edit region' });
  await expect(editing.getByLabel('Code')).toHaveCount(0);
  await editing.getByLabel('Name').fill(renamed);
  await editing.getByRole('button', { name: 'Save' }).click();
  const row = page.getByRole('row', { name: new RegExp(firstCode) });
  await expect(row).toContainText(renamed);

  await page.getByRole('button', { name: `Deactivate ${renamed}` }).click();
  await expect(row.getByText('Inactive', { exact: true })).toBeVisible();

  await page.reload();
  await expect(row.getByText('Inactive', { exact: true })).toBeVisible();
  expect(await namesInOrder(page, [renamed, second])).toEqual([second, renamed]);

  await page.getByRole('button', { name: `Activate ${renamed}` }).click();
  await expect(row.getByText('Active', { exact: true })).toBeVisible();
  await page.reload();
  await expect(row.getByText('Active', { exact: true })).toBeVisible();
  await expectAccessible(page);
});

test('an author has no way to the trims and regions screens', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page).toHaveURL('/dashboard');
  await expect(page.getByRole('link', { name: 'Trims' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Regions' })).toHaveCount(0);

  for (const address of ['/admin/trims', '/admin/regions']) {
    await page.goto(address);
    await expect(page).toHaveURL('/dashboard');
  }
});
