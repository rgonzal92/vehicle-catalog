import { expect, type Page } from '@playwright/test';
import { createWorkingCopy, expectAccessible, nextSave, signIn, signOut, test } from './support';

/** Submits the working copy that the editor shows, with a note. */
async function submit(page: Page, note: string): Promise<void> {
  await page.getByRole('button', { name: 'Submit for review' }).click();
  const dialog = page.getByRole('dialog', { name: 'Submit for review' });
  await dialog.getByLabel('Note for the reviewer (optional)').fill(note);
  await dialog.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Withdraw' })).toBeVisible();
}

test('a manager finds a submitted catalog in the review queue and sees what it changes', async ({
  page,
}) => {
  await signIn(page, 'author');
  const name = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  // The 2.0L turbo is Available on Sport in North America; the owner makes it Standard.
  const saved = nextSave(page);
  await page
    .locator('app-availability-matrix')
    .getByRole('row', { name: /ENGINE_20T_I4/ })
    .getByRole('cell')
    .nth(2)
    .focus();
  await page.keyboard.press('s');
  expect(await saved).toBe(200);
  await submit(page, 'Winter content, ready.');
  await signOut(page);

  await signIn(page, 'manager');
  const queue = page.getByRole('region', { name: 'Review queue' });
  const listed = queue.getByRole('row', { name });
  await expect(listed).toContainText('Compact SUV');
  await expect(listed).toContainText('Winter content, ready.');
  await expectAccessible(page);

  await listed.getByRole('link', { name: new RegExp(`^Review ${name} by `) }).click();
  await expect(page).toHaveURL(/\/catalogs\/\d+\/review$/);
  await expect(page.getByRole('heading', { name })).toBeVisible();
  await expect(page.locator('dl').getByText('Owner', { exact: true })).toBeVisible();
  await expect(page.locator('[data-submit-note]')).toHaveText('Winter content, ready.');
  await expect(page.locator('[data-base]')).toHaveText('Approved v2');

  // What the owner changed is listed, and marked in the matrix with what it was.
  const changes = page.getByRole('region', { name: 'What it changes' });
  await expect(
    changes.getByRole('row', {
      name: /^2\.0L Turbo I4 Engine \(ENGINE_20T_I4\) Sport in North America Available Standard$/,
    }),
  ).toBeVisible();
  const matrix = page.locator('app-availability-matrix');
  const changed = matrix
    .getByRole('row', { name: /ENGINE_20T_I4/ })
    .getByRole('cell')
    .nth(2);
  await expect(changed).toHaveText(/^\s*S\s+was A/);
  await expect(changed).toHaveAttribute('title', 'Was Available');
  // The reviewer reads the catalog and cannot edit it.
  await changed.click();
  await expect(matrix.getByRole('combobox')).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Issues' })).toBeVisible();
  await expectAccessible(page);
  await signOut(page);

  // Withdrawn, it leaves the queue and is its owner's alone again.
  await signIn(page, 'author');
  await page
    .getByRole('region', { name: 'My catalogs' })
    .getByRole('button', { name: `Withdraw ${name}` })
    .click();
  await expect(
    page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name }),
  ).toContainText('Draft');
  await signOut(page);
  await signIn(page, 'manager');
  await expect(page.getByRole('region', { name: 'Review queue' })).toBeVisible();
  await expect(
    page.getByRole('region', { name: 'Review queue' }).getByRole('row', { name }),
  ).toHaveCount(0);
});

test("a manager's own submission is in the queue, marked as theirs, with nothing to open", async ({
  page,
}) => {
  await signIn(page, 'manager');
  const name = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2027');
  await submit(page, '');

  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Dashboard' })
    .click();
  const listed = page.getByRole('region', { name: 'Review queue' }).getByRole('row', { name });
  await expect(listed).toContainText('Yours');
  await expect(listed.getByRole('link')).toHaveCount(0);

  await page
    .getByRole('region', { name: 'My catalogs' })
    .getByRole('button', { name: `Withdraw ${name}` })
    .click();
  await expect(listed).toHaveCount(0);
});

test('an author cannot open the review of a catalog', async ({ page }) => {
  await signIn(page, 'author');
  const name = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const editor = page.url();

  await page.goto(`${editor}/review`);

  await expect(page).toHaveURL('/dashboard');
  await expect(
    page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name }),
  ).toBeVisible();
});
