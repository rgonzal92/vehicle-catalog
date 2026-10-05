import { expect, test, type Page } from '@playwright/test';
import { expectAccessible, signIn } from './support';

/** The library's two ordered lists as their screens word them. Only a region has a code. */
const lists = [
  { title: 'Trims', singular: 'trim', address: '/admin/trims', hasCode: false },
  { title: 'Regions', singular: 'region', address: '/admin/regions', hasCode: true },
];

/** The names in the list's rows, top to bottom, limited to the ones this test made. */
async function namesInOrder(page: Page, mine: string[]): Promise<string[]> {
  const rows = await page.locator('tbody tr').allTextContents();

  return rows.flatMap((row) => mine.filter((name) => row.includes(name)));
}

for (const { title, singular, address, hasCode } of lists) {
  test(`an admin adds, renames, moves, deactivates, and reactivates a ${singular}`, async ({
    page,
  }) => {
    const unique = Date.now();
    const first = { code: `A${unique}`, name: `First ${singular} ${unique}` };
    const second = { code: `B${unique}`, name: `Second ${singular} ${unique}` };
    const renamed = `Renamed ${singular} ${unique}`;

    await signIn(page, 'admin');
    await page.getByRole('link', { name: title }).click();
    await expect(page).toHaveURL(address);
    await expect(page.getByRole('heading', { name: title })).toBeVisible();

    const adding = page.getByRole('dialog', { name: `Add ${singular}` });
    for (const { code, name } of [first, second]) {
      await page.getByRole('button', { name: `Add ${singular}` }).click();
      if (hasCode) {
        await adding.getByLabel('Code').fill(code);
      }
      await adding.getByLabel('Name').fill(name);
      await adding.getByRole('button', { name: 'Save' }).click();
      await expect(page.getByRole('row', { name: new RegExp(name) })).toBeVisible();
    }
    expect(await namesInOrder(page, [first.name, second.name])).toEqual([first.name, second.name]);

    // A region's code is already in use; a trim's name is, whatever its case.
    await page.getByRole('button', { name: `Add ${singular}` }).click();
    if (hasCode) {
      await adding.getByLabel('Code').fill(first.code);
      await adding.getByLabel('Name').fill(`Another ${first.name}`);
    } else {
      await adding.getByLabel('Name').fill(first.name.toUpperCase());
    }
    await expectAccessible(page);
    await adding.getByRole('button', { name: 'Save' }).click();
    await expect(adding.getByRole('alert')).toContainText('already uses this');
    await adding.getByRole('button', { name: 'Cancel' }).click();

    await page.getByRole('button', { name: `Move ${second.name} up` }).click();
    await expect
      .poll(() => namesInOrder(page, [first.name, second.name]))
      .toEqual([second.name, first.name]);

    await page.getByRole('button', { name: `Edit ${first.name}` }).click();
    const editing = page.getByRole('dialog', { name: `Edit ${singular}` });
    await expect(editing.getByLabel('Code')).toHaveCount(0);
    await editing.getByLabel('Name').fill(renamed);
    await editing.getByRole('button', { name: 'Save' }).click();
    const row = page.getByRole('row', { name: new RegExp(renamed) });
    await expect(row).toBeVisible();
    if (hasCode) {
      await expect(row).toContainText(first.code);
    }

    await page.getByRole('button', { name: `Deactivate ${renamed}` }).click();
    await expect(row.getByText('Inactive', { exact: true })).toBeVisible();

    await page.reload();
    await expect(row.getByText('Inactive', { exact: true })).toBeVisible();
    expect(await namesInOrder(page, [renamed, second.name])).toEqual([second.name, renamed]);

    await page.getByRole('button', { name: `Activate ${renamed}` }).click();
    await expect(row.getByText('Active', { exact: true })).toBeVisible();
    await page.reload();
    await expect(row.getByText('Active', { exact: true })).toBeVisible();
    await expectAccessible(page);
  });
}

test('an author has no way to the trims and regions screens', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page).toHaveURL('/dashboard');

  for (const { title, address } of lists) {
    await expect(page.getByRole('link', { name: title })).toHaveCount(0);
    await page.goto(address);
    await expect(page).toHaveURL('/dashboard');
  }
});
