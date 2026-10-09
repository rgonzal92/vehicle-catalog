import { expect } from '@playwright/test';
import { createWorkingCopy, expectAccessible, signIn, test, unique } from './support';

test('the owner renames a working copy in the editor header and deletes it from the dashboard', async ({
  page,
}) => {
  await signIn(page, 'author');
  const other = await createWorkingCopy(page, 'Car', 'Sports Coupe', '2026');
  await page.getByRole('link', { name: 'Dashboard' }).click();
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const editor = page.url();

  // A name the owner already uses is refused, whatever its case.
  await page.getByRole('button', { name: 'Rename' }).click();
  await page.getByLabel('Name', { exact: true }).fill(other.toUpperCase());
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(
    page.getByText('Another of your working copies already has this name.'),
  ).toBeVisible();
  await expectAccessible(page);

  // The new name survives a reload and shows on the dashboard.
  const renamed = unique('Renamed');
  await page.getByLabel('Name', { exact: true }).fill(renamed);
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('heading', { name: renamed })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: renamed })).toBeVisible();
  await page.getByRole('link', { name: 'Dashboard' }).click();
  const mine = page.getByRole('region', { name: 'My catalogs' });
  await expect(mine.getByRole('row', { name: renamed })).toBeVisible();

  // Deleting asks first.
  await mine.getByRole('button', { name: `Delete ${renamed}` }).click();
  const question = page.getByRole('dialog', { name: 'Delete working copy' });
  await expect(question).toContainText(`Delete ${renamed}?`);
  await expectAccessible(page);
  await question.getByRole('button', { name: 'Keep' }).click();
  await expect(mine.getByRole('row', { name: renamed })).toBeVisible();

  await mine.getByRole('button', { name: `Delete ${renamed}` }).click();
  await question.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(mine.getByRole('row', { name: renamed })).toHaveCount(0);
  await expect(mine.getByRole('row', { name: other })).toBeVisible();

  // It is gone, and the Approved version it was copied from is not.
  await page.goto(editor);
  await expect(page.getByText('There is no catalog at this address.')).toBeVisible();
  await page.getByRole('link', { name: 'Dashboard' }).click();
  await expect(
    page
      .getByRole('region', { name: 'Approved catalogs' })
      .getByRole('link', { name: 'Open Compact SUV 2026', exact: true }),
  ).toBeVisible();
});
