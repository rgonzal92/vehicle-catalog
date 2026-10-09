import { expect, test, type Page } from '@playwright/test';
import { createWorkingCopy, expectAccessible, signIn, signOut } from './support';

// Each of these tests signs in as an owner and as a reviewer in turn, which takes its time.
test.describe.configure({ timeout: 60_000 });

/** Submits the working copy that the editor shows, with a note if there is one. */
async function submit(page: Page, note = ''): Promise<void> {
  await page.getByRole('button', { name: 'Submit for review' }).click();
  const dialog = page.getByRole('dialog', { name: 'Submit for review' });
  await dialog.getByLabel('Note for the reviewer (optional)').fill(note);
  await dialog.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Withdraw' })).toBeVisible();
}

/** Opens the review of a catalog from the manager's review queue. */
async function review(page: Page, name: string): Promise<void> {
  await page
    .getByRole('region', { name: 'Review queue' })
    .getByRole('row', { name })
    .getByRole('link', { name: new RegExp(`^Review ${name} by `) })
    .click();
  await expect(page.getByRole('heading', { name })).toBeVisible();
}

// The sedan's 2028 lineage is this test's own: no other test reads or approves it.
test("a manager approves a submitted catalog, which becomes its lineage's Approved version", async ({
  page,
}) => {
  await signIn(page, 'author');
  const name = await createWorkingCopy(page, 'Car', 'Sedan', '2028');
  await submit(page, 'The 2028 sedan, carried over.');
  await signOut(page);

  await signIn(page, 'manager');
  await review(page, name);
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Approve catalog' });
  await dialog.getByLabel('Comment (optional)').fill('Good to go.');
  await expectAccessible(page);
  await dialog.getByRole('button', { name: 'Approve', exact: true }).click();

  // The reviewer is back on the dashboard, where the catalog has left the queue and is Approved.
  await expect(page).toHaveURL('/dashboard');
  await expect(page.getByRole('region', { name: 'Review queue' })).toBeVisible();
  await expect(
    page.getByRole('region', { name: 'Review queue' }).getByRole('row', { name }),
  ).toHaveCount(0);
  const approved = page
    .getByRole('region', { name: 'Approved catalogs' })
    .getByRole('row', { name: /Sedan/ })
    .filter({ has: page.getByRole('cell', { name: '2028', exact: true }) });
  await expect(approved.getByRole('cell').nth(2)).toHaveText('1');
  await signOut(page);

  // Its owner no longer has it as a working copy, and everyone sees the version.
  await signIn(page, 'author');
  await expect(page.getByRole('region', { name: 'My catalogs' })).toBeVisible();
  await expect(
    page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name }),
  ).toHaveCount(0);
  await page.getByRole('link', { name: 'Open Sedan 2028', exact: true }).click();
  await expect(page.getByText('Approved version 1')).toBeVisible();
  await expect(page.locator('[data-shown]')).toContainText(name);
});

test('a manager rejects a submitted catalog with a reason, and its owner has it back', async ({
  page,
}) => {
  await signIn(page, 'author');
  const name = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  await submit(page);
  await signOut(page);

  await signIn(page, 'manager');
  await review(page, name);
  await page.getByRole('button', { name: 'Reject', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Reject catalog' });
  const reject = dialog.getByRole('button', { name: 'Reject', exact: true });
  await expect(reject).toBeDisabled();
  await dialog.getByLabel('Why it is rejected').fill('The hybrid needs its battery cooling.');
  await expectAccessible(page);
  await reject.click();
  await expect(page).toHaveURL('/dashboard');
  await expect(page.getByRole('region', { name: 'Review queue' })).toBeVisible();
  await expect(
    page.getByRole('region', { name: 'Review queue' }).getByRole('row', { name }),
  ).toHaveCount(0);
  await signOut(page);

  await signIn(page, 'author');
  const mine = page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name });
  await expect(mine).toContainText('Draft');
  await expect(mine.getByRole('button', { name: `Delete ${name}` })).toBeVisible();

  // The editor says why it came back, and the History keeps the decision among the changes.
  await mine.getByRole('link', { name: `Open ${name}` }).click();
  const decision = page.locator('[data-notice="decision"]');
  await expect(decision).toContainText('Rejected by');
  await expect(decision).toContainText('The hybrid needs its battery cooling.');
  await page.getByRole('tab', { name: 'History' }).click();
  const history = page.getByRole('tabpanel', { name: 'History' });
  await expect(
    history.getByRole('row', { name: /Rejected The hybrid needs its battery cooling\.$/ }),
  ).toBeVisible();
  await expect(history.getByRole('row', { name: /Submitted$/ })).toBeVisible();
  await expectAccessible(page);
});

// The compact SUV's 2028 lineage is this test's own: no other test reads or approves it.
test('a catalog that another approval leaves behind is returned to its owner and marked stale', async ({
  page,
}) => {
  await signIn(page, 'author');
  const approved = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2028');
  await submit(page);
  await signOut(page);

  // The manager has a catalog of the same lineage waiting for review too, and approves the other.
  await signIn(page, 'manager');
  const name = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2028');
  await submit(page);
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Dashboard' })
    .click();
  await review(page, approved);
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  await page
    .getByRole('dialog', { name: 'Approve catalog' })
    .getByRole('button', { name: 'Approve', exact: true })
    .click();
  await expect(page).toHaveURL('/dashboard');

  const mine = page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name });
  await expect(mine).toContainText('Draft');
  await expect(mine).toContainText('Stale');
  await mine.getByRole('link', { name: `Open ${name}` }).click();
  await expect(page.locator('[data-notice="decision"]')).toContainText(
    'another catalog of its lineage was approved first',
  );
  await expect(page.locator('[data-notice="stale"]')).toContainText(
    'Approved v1 is now the current version of Compact SUV 2028',
  );
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled();
  await expect(
    page.getByText('It is stale, and has to be updated from the current Approved version first.'),
  ).toBeVisible();
  await expectAccessible(page);
});

test('nobody decides on a catalog of their own', async ({ page }) => {
  await signIn(page, 'manager');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2027');
  await submit(page);

  await page.goto(`${page.url()}/review`);

  await expect(page.getByRole('button', { name: 'Approve', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Reject', exact: true })).toBeDisabled();
  await expect(
    page.getByText('This catalog is yours, and nobody decides on their own.'),
  ).toBeVisible();
});
