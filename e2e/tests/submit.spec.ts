import { expect } from '@playwright/test';
import { createWorkingCopy, expectAccessible, nextSave, signIn, test } from './support';

test('an owner submits a working copy with a note, and withdraws it to edit it again', async ({
  page,
}) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const matrix = page.locator('app-availability-matrix');
  const status = page.locator('dl').getByText('Draft', { exact: true });
  await expect(status).toBeVisible();

  await page.getByRole('button', { name: 'Submit for review' }).click();
  const dialog = page.getByRole('dialog', { name: 'Submit for review' });
  await dialog.getByLabel('Note for the reviewer (optional)').fill('Winter content, ready.');
  await expectAccessible(page);
  await dialog.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(dialog).toBeHidden();

  // A Submitted catalog shows its note and takes no edits.
  await expect(page.locator('dl').getByText('Submitted', { exact: true }).first()).toBeVisible();
  await expect(page.locator('[data-submit-note]')).toHaveText('Winter content, ready.');
  await expect(page.getByRole('button', { name: 'Submit for review' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Rename' })).toHaveCount(0);
  await matrix
    .getByRole('row', { name: /ENGINE_20T_I4/ })
    .getByRole('cell')
    .nth(2)
    .click();
  await expect(matrix.getByRole('combobox')).toHaveCount(0);
  await expectAccessible(page);

  await page.getByRole('tab', { name: 'History' }).click();
  await expect(
    page
      .getByRole('tabpanel', { name: 'History' })
      .getByRole('row', { name: /Submitted Winter content, ready\.$/ }),
  ).toBeVisible();

  // Withdrawn, it is a Draft again and takes edits.
  await page.getByRole('button', { name: 'Withdraw' }).click();
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled();
  await page.getByRole('tab', { name: 'Features' }).click();
  const saved = nextSave(page);
  await matrix
    .getByRole('row', { name: /ENGINE_20T_I4/ })
    .getByRole('cell')
    .nth(2)
    .focus();
  await page.keyboard.press('s');
  expect(await saved).toBe(200);
});

test('a catalog with Errors cannot be submitted, and one that is Submitted is withdrawn from the dashboard', async ({
  page,
}) => {
  await signIn(page, 'manager');
  await createWorkingCopy(page, 'Car', 'Sports Coupe', '2027');
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled();
  await expect(page.getByText('It has 3 Errors, which must be put right first.')).toBeVisible();
  await expectAccessible(page);

  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Dashboard' })
    .click();
  const name = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2027');
  await page.getByRole('button', { name: 'Submit for review' }).click();
  await page
    .getByRole('dialog', { name: 'Submit for review' })
    .getByRole('button', { name: 'Submit', exact: true })
    .click();
  await expect(page.getByRole('button', { name: 'Withdraw' })).toBeVisible();

  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Dashboard' })
    .click();
  const listed = page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name });
  await expect(listed).toContainText('Submitted');
  await expect(listed.getByRole('button', { name: `Delete ${name}` })).toHaveCount(0);
  await listed.getByRole('button', { name: `Withdraw ${name}` }).click();
  await expect(listed).toContainText('Draft');
  await expect(listed.getByRole('button', { name: `Delete ${name}` })).toBeVisible();
});
