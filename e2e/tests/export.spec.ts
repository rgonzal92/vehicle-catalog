import { expect, test } from '@playwright/test';
import { createWorkingCopy, expectAccessible, signIn } from './support';

test('an author exports an Approved catalog and downloads the spreadsheet', async ({ page }) => {
  await signIn(page, 'author');
  await page.getByRole('link', { name: 'Open Compact SUV 2026', exact: true }).click();
  await expect(page.getByText('Approved version 2')).toBeVisible();

  await page.getByRole('button', { name: 'Export' }).click();
  const dialog = page.getByRole('dialog', { name: 'Export to a spreadsheet' });
  // The file is built in the background, and the dialog asks after it every two seconds.
  await expect(dialog.getByText('The spreadsheet is being prepared')).toBeVisible();
  await expectAccessible(page);
  await expect(dialog.getByText('Compact SUV 2026 v2.xlsx is ready.')).toBeVisible();
  await expectAccessible(page);

  const downloading = page.waitForEvent('download');
  await dialog.getByRole('link', { name: 'Download' }).click();
  const download = await downloading;
  expect(download.suggestedFilename()).toBe('Compact SUV 2026 v2.xlsx');
  expect(await download.failure()).toBeNull();
});

test('the owner of a working copy exports it from the catalog editor', async ({ page }) => {
  await signIn(page, 'author');
  const name = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');

  await page.getByRole('button', { name: 'Export' }).click();
  const dialog = page.getByRole('dialog', { name: 'Export to a spreadsheet' });
  // A file's name keeps letters, digits, spaces, dots, dashes, and underscores.
  const fileName = `Compact SUV 2026 ${name.replace(/[^A-Za-z0-9 ._-]/g, '_')}.xlsx`;
  await expect(dialog.getByText(`${fileName} is ready.`)).toBeVisible();

  const downloading = page.waitForEvent('download');
  await dialog.getByRole('link', { name: 'Download' }).click();
  expect((await downloading).suggestedFilename()).toBe(fileName);
});
