import { expect, test } from '@playwright/test';
import { choose, expectAccessible, signIn } from './support';

test('an admin adds, changes, and deletes a global rule', async ({ page }) => {
  await signIn(page, 'admin');
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Global rules' })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Global rules');
  await expect(
    page.getByRole('row', { name: /^Tow Package Requires Heavy-Duty Cooling Every region/ }),
  ).toBeVisible();
  await expectAccessible(page);

  await page.getByRole('button', { name: 'Add global rule' }).click();
  const dialog = page.getByRole('dialog', { name: 'Add global rule' });
  await expect(dialog.getByRole('button', { name: 'Save' })).toBeDisabled();
  await choose(dialog, 'Source', 'Panoramic Roof');
  // The list of several is opened by its box: its labelled part is there for the keyboard alone.
  await dialog.getByText('Choose features').click();
  await page.getByRole('option', { name: 'Leather Seats', exact: true }).click();
  await page.getByRole('option', { name: 'Premium Audio', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await expect(dialog).toBeVisible();
  await expectAccessible(page);
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  const added = page.getByRole('row', {
    name: /Panoramic Roof Requires Premium Audio, Leather Seats Every region/,
  });
  await expect(added).toBeVisible();

  await added.getByRole('button', { name: /^Edit the rule/ }).click();
  const editing = page.getByRole('dialog', { name: 'Edit global rule' });
  await expect(editing.getByText("A rule's kind cannot be changed.")).toBeVisible();
  await editing.getByRole('checkbox', { name: 'The rule holds in every region' }).uncheck();
  await editing.getByText('Choose regions').click();
  await page.getByRole('option', { name: 'Europe', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await editing.getByRole('button', { name: 'Save' }).click();
  await expect(editing).toBeHidden();
  const changed = page.getByRole('row', {
    name: /Panoramic Roof Requires Premium Audio, Leather Seats Europe/,
  });
  await expect(changed).toBeVisible();

  await changed.getByRole('button', { name: /^Delete the rule/ }).click();
  const asking = page.getByRole('dialog', { name: 'Delete global rule' });
  await expect(asking).toContainText(
    'Delete the rule that Panoramic Roof requires Premium Audio, Leather Seats?',
  );
  await asking.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(asking).toBeHidden();
  await expect(page.getByRole('row', { name: /Panoramic Roof Requires/ })).toHaveCount(0);
});

test('a feature that a global rule names cannot be retired', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/admin/features');
  await page.getByLabel('Code or name').fill('COOLING_HEAVY_DUTY');
  await page.getByRole('button', { name: 'Search' }).click();

  await page.getByRole('button', { name: 'Retire Heavy-Duty Cooling' }).click();

  await expect(page.locator('p-toast')).toContainText(
    'Heavy-Duty Cooling is named by 2 global rules. Change or delete them first.',
  );
  await expect(page.getByRole('row', { name: /COOLING_HEAVY_DUTY/ })).toContainText('Active');
});

test('only an admin is offered the global rules', async ({ page }) => {
  await signIn(page, 'author');
  await expect(page.getByRole('link', { name: 'Global rules' })).toHaveCount(0);

  await page.goto('/admin/global-rules');

  await expect(page).toHaveURL('/dashboard');
});
