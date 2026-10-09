import { expect, test, type Page } from '@playwright/test';
import { choose, createWorkingCopy, expectAccessible, nextSave, signIn, signOut } from './support';

// Three people sign in, one after the other, and a catalog is built from nothing.
test.describe.configure({ timeout: 90_000 });

/** The lineage's row in the dashboard's list of Approved catalogs. */
const fullSizeSuv = (page: Page) =>
  page
    .getByRole('region', { name: 'Approved catalogs' })
    .getByRole('row', { name: /Full-Size SUV/ })
    .filter({ has: page.getByRole('cell', { name: '2026', exact: true }) });

// The full-size SUV's 2026 lineage is this test's own, and so are the two electric motors: no
// seeded catalog and no other test's catalog offers either, so the rule between them breaks
// nothing else in the database the tests share.
test('a global rule that an Approved catalog breaks flags its lineage on the dashboard', async ({
  page,
}) => {
  // An author builds a catalog that has both motors Standard on its one offering.
  await signIn(page, 'author');
  const name = await createWorkingCopy(page, 'SUV', 'Full-Size SUV', '2026');
  await page.getByRole('button', { name: 'Manage trims and regions' }).click();
  const managing = page.getByRole('dialog', { name: 'Manage trims and regions' });
  await choose(managing, 'Add a trim', 'Base');
  await expect(managing.getByRole('rowheader', { name: 'Base' })).toBeVisible();
  await choose(managing, 'Add a region', 'North America');
  await managing.getByRole('checkbox', { name: 'Base is sold in North America' }).click();
  await expect(
    managing.getByRole('checkbox', { name: 'Base is sold in North America' }),
  ).toBeChecked();
  await managing.getByRole('button', { name: 'Done' }).click();

  await page.getByRole('button', { name: 'Add features' }).click();
  const picking = page.getByRole('dialog', { name: 'Add features' });
  await picking.getByLabel('Code or name').fill('electric motor');
  await picking.getByRole('button', { name: 'Search' }).click();
  await picking.getByRole('checkbox', { name: 'Add Single Electric Motor' }).check();
  await picking.getByRole('checkbox', { name: 'Add Dual Electric Motor' }).check();
  await picking.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(picking).toBeHidden();

  const matrix = page.locator('app-availability-matrix');
  for (const motor of [/MOTOR_SINGLE/, /MOTOR_DUAL/]) {
    const saved = nextSave(page);
    await matrix.getByRole('row', { name: motor }).getByRole('cell').nth(1).focus();
    await page.keyboard.press('s');
    expect(await saved).toBe(200);
  }
  await page.getByRole('button', { name: 'Submit for review' }).click();
  await page
    .getByRole('dialog', { name: 'Submit for review' })
    .getByRole('button', { name: 'Submit', exact: true })
    .click();
  await expect(page.getByRole('button', { name: 'Withdraw' })).toBeVisible();
  await signOut(page);

  // A manager approves it, and it is not flagged: the approval found no Error.
  await signIn(page, 'manager');
  await page
    .getByRole('region', { name: 'Review queue' })
    .getByRole('row', { name })
    .getByRole('link', { name: new RegExp(`^Review ${name} by `) })
    .click();
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  await page
    .getByRole('dialog', { name: 'Approve catalog' })
    .getByRole('button', { name: 'Approve', exact: true })
    .click();
  await expect(page).toHaveURL('/dashboard');
  await expect(fullSizeSuv(page).getByRole('cell').nth(2)).toHaveText('1');
  await signOut(page);

  // An admin adds a global rule by which the two motors exclude each other.
  await signIn(page, 'admin');
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Global rules' })
    .click();
  await page.getByRole('button', { name: 'Add global rule' }).click();
  const adding = page.getByRole('dialog', { name: 'Add global rule' });
  await choose(adding, 'Kind', 'Excludes');
  await choose(adding, 'Source', 'Single Electric Motor');
  await adding.getByText('Choose features').click();
  await page.getByRole('option', { name: 'Dual Electric Motor', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding).toBeHidden();

  try {
    // Nobody opens the catalog. The worker checks it, and the dashboard says what it found.
    await page
      .getByRole('navigation', { name: 'Main' })
      .getByRole('link', { name: 'Dashboard' })
      .click();
    await expect(async () => {
      await page.reload();
      await expect(fullSizeSuv(page).getByRole('cell').nth(2)).toContainText('Needs revision', {
        timeout: 2000,
      });
    }).toPass();
    await expect(fullSizeSuv(page).getByRole('cell').nth(2)).toContainText(/\d+ Errors?/);
    await expectAccessible(page);
  } finally {
    // The rule goes again, whatever became of the test, and the flag with it.
    await page
      .getByRole('navigation', { name: 'Main' })
      .getByRole('link', { name: 'Global rules' })
      .click();
    await page
      .getByRole('row', { name: /^Single Electric Motor Excludes .*Dual Electric Motor/ })
      .getByRole('button', { name: /^Delete the rule/ })
      .click();
    const asking = page.getByRole('dialog', { name: 'Delete global rule' });
    await asking.getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(asking).toBeHidden();
  }

  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Dashboard' })
    .click();
  await expect(async () => {
    await page.reload();
    await expect(fullSizeSuv(page).getByRole('cell').nth(2)).toHaveText('1', { timeout: 2000 });
  }).toPass();
});
