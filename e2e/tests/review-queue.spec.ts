import { expect, test, type Page } from '@playwright/test';
import { createWorkingCopy, expectAccessible, signIn, signOut } from './support';

/** Submits the working copy that the editor shows, with a note. */
async function submit(page: Page, note: string): Promise<void> {
  await page.getByRole('button', { name: 'Submit for review' }).click();
  const dialog = page.getByRole('dialog', { name: 'Submit for review' });
  await dialog.getByLabel('Note for the reviewer (optional)').fill(note);
  await dialog.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Withdraw' })).toBeVisible();
}

test('a manager finds a submitted catalog in the review queue and opens it read-only', async ({
  page,
}) => {
  await signIn(page, 'author');
  const name = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  await submit(page, 'Winter content, ready.');
  await signOut(page);

  await signIn(page, 'manager');
  const queue = page.getByRole('region', { name: 'Review queue' });
  const listed = queue.getByRole('row', { name });
  await expect(listed).toContainText('Compact SUV');
  await expect(listed).toContainText('Winter content, ready.');
  await expectAccessible(page);

  await listed.getByRole('link', { name: new RegExp(`^Open ${name} by `) }).click();
  await expect(page.getByRole('heading', { name })).toBeVisible();
  await expect(page.locator('dl').getByText('Owner', { exact: true })).toBeVisible();
  await expect(page.locator('[data-submit-note]')).toHaveText('Winter content, ready.');
  // The reviewer reads the catalog and can do nothing to it here.
  await expect(page.getByRole('button', { name: 'Withdraw' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Rename' })).toHaveCount(0);
  const matrix = page.locator('app-availability-matrix');
  await matrix
    .getByRole('row', { name: /ENGINE_20T_I4/ })
    .getByRole('cell')
    .nth(2)
    .click();
  await expect(matrix.getByRole('combobox')).toHaveCount(0);
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
