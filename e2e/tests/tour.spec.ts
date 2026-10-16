import { expect, type Page } from '@playwright/test';
import { createWorkingCopy, expectAccessible, signIn, signOut, test } from './support';

test.use({ offersTour: true });

/** The landing page's offer of a tour. */
const offer = (page: Page) => page.locator('.p-toast-message', { hasText: 'New here?' });

/** The step of the tour with this title. */
const step = (page: Page, title: string) => page.getByRole('dialog', { name: title });

/**
 * The cell of the working copy's matrix that a feature has on Luxury in North America. Its ring can
 * take as long as the tour waits for an element, 10 s, on a machine running every test at once.
 */
const cell = (page: Page, feature: string) => page.locator(`td[data-cell="${feature} Luxury NA"]`);

/** The name of the working copy a tour made, as the editor's heading shows it. */
const tourCopy = async (page: Page) => {
  const heading = page.getByRole('heading', { name: /^Tour copy \d\d:\d\d:\d\d( \(\d\))?$/ });
  await expect(heading).toBeVisible();
  return (await heading.textContent())!.trim();
};

/**
 * The row of My catalogs that lists a working copy, by its name. Other tests sign in as the manager
 * at the same time, so a test looks for its own copy and not for every tour's.
 */
const listed = (page: Page, name: string) =>
  page.getByRole('region', { name: 'My catalogs' }).getByRole('cell', { name, exact: true });

/** Resolves once the dashboard has read the person's working copies. */
const myCatalogsRead = (page: Page) =>
  page.waitForResponse((response) => response.url().includes('/api/catalogs?scope=mine'));

/** The titles of the steps someone signed in goes through, in order, up to the analyst's. */
const STEPS = [
  'What this app is for',
  'Your dashboard',
  'A published catalog',
  'Make a working copy',
  'Break a rule',
  'Put it right',
  "A manager's review",
  'Ask the analyst',
];

/** Takes the tour from the header, signed in, going on with Enter until the step given is open. */
async function walkTo(page: Page, title: string): Promise<void> {
  await page.getByRole('button', { name: 'Take the tour' }).click();
  for (const before of STEPS.slice(0, STEPS.indexOf(title))) {
    await expect(step(page, before).getByRole('button', { name: 'Next' })).toBeFocused();
    await page.keyboard.press('Enter');
  }
  await expect(step(page, title)).toBeVisible();
}

/** Submits the working copy that the editor shows. */
async function submit(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Submit for review' }).click();
  const dialog = page.getByRole('dialog', { name: 'Submit for review' });
  await dialog.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Withdraw' })).toBeVisible();
}

/** The analyst's question that the tour asks. */
const QUESTION = 'What changed between the two Approved versions of the 2026 Compact SUV?';

test('a visitor takes the tour from its offer to its last card, going on with Enter', async ({
  page,
}) => {
  // A catalog waits for review, as one does on the live site, where the demo starts with one.
  await signIn(page, 'author');
  const waiting = await createWorkingCopy(page, 'SUV', 'Compact SUV', '2027');
  await submit(page);
  await signOut(page);

  await offer(page).getByRole('button', { name: 'Take the tour' }).click();

  const about = step(page, 'What this app is for');
  await expect(about).toContainText('a major US carmaker');
  await expect(about).toContainText('1 of 9');
  await expect(page.getByRole('heading', { name: 'Vehicle Catalog' })).toHaveClass(/tour-target/);
  await expect(about.getByRole('button', { name: 'Next' })).toBeFocused();
  await page.keyboard.press('Enter');

  const signingIn = step(page, 'Sign in as the demo manager');
  await expect(signingIn.getByRole('definition')).toHaveText(['manager']);
  await expect(signingIn.getByRole('button', { name: 'Copy the username' })).toBeVisible();
  await expect(signingIn.getByRole('button', { name: 'Sign in' })).toBeFocused();
  await page.keyboard.press('Enter');
  await page.locator('input[name="username"]').fill('manager');
  await page.locator('input[type="submit"]').click();

  const dashboard = step(page, 'Your dashboard');
  await expect(dashboard).toContainText('3 of 9');
  await expect(page).toHaveURL('/dashboard');
  await expect(dashboard.getByRole('button', { name: 'Next' })).toBeFocused();
  await page.keyboard.press('Enter');

  await expect(step(page, 'A published catalog')).toBeVisible();
  await expect(page).toHaveURL(/\/approved\/\d+$/);
  await page.keyboard.press('Enter');

  await expect(step(page, 'Make a working copy')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Create working copy' })).toHaveClass(
    /tour-target/,
  );
  await page.keyboard.press('Enter');

  const breaking = step(page, 'Break a rule');
  await expect(page).toHaveURL(/\/catalogs\/\d+$/);
  const name = await tourCopy(page);
  await expect(cell(page, 'SEAT_HEATED_FRONT')).toHaveClass(/tour-target/, { timeout: 10_000 });
  await expect(breaking.getByRole('button', { name: 'Next' })).toBeFocused();
  await page.keyboard.press('Enter');

  // Heated rear seats need heated front seats: the rule's Error is marked on the rear seats.
  await expect(step(page, 'Put it right')).toBeVisible();
  await expect(cell(page, 'SEAT_HEATED_FRONT')).toContainText('-');
  await expect(cell(page, 'SEAT_HEATED_REAR')).toHaveClass(/matrix-error/);
  await expect(page.locator('[data-issue-counts]')).toContainText('1 Error');
  await expect(page.locator('[data-issue-counts]')).toHaveClass(/tour-target/);
  // Next sets the cell back and goes on to the next page, so what the save answers is looked at.
  const restored = page.waitForResponse(
    (response) => response.request().method() === 'PUT' && response.url().endsWith('/cells'),
  );
  await page.keyboard.press('Enter');
  const { issues } = (await (await restored).json()) as { issues: { severity: string }[] };
  expect(issues.filter(({ severity }) => severity === 'ERROR')).toEqual([]);

  const review = step(page, "A manager's review");
  await expect(review).toContainText('The tour leaves this decision for you or the next visitor.');
  await expect(page).toHaveURL(/\/catalogs\/\d+\/review$/);
  await expect(page.locator('[data-tour="decide"]')).toHaveClass(/tour-target/);
  await expect(review.getByRole('button', { name: 'Next' })).toBeFocused();
  await page.keyboard.press('Enter');

  const analyst = step(page, 'Ask the analyst');
  await expect(page).toHaveURL('/analyst');
  await expect(page.getByLabel('Your question')).toHaveValue(QUESTION);
  await expect(analyst.getByRole('button', { name: 'Next' })).toBeFocused();
  await page.keyboard.press('Enter');
  const answer = page.locator('[data-by="ANALYST"]');
  await expect(answer).toContainText('An answer from the stand-in');
  await expect(answer).toHaveClass(/tour-target/);
  await expect(analyst.getByRole('button', { name: 'Next' })).toBeFocused();
  await page.keyboard.press('Enter');

  const card = step(page, "That's the tour");
  await expect(card.getByRole('link', { name: 'View the code' })).toHaveAttribute(
    'href',
    'https://github.com/rgonzal92/vehicle-catalog',
  );
  await expect(card.getByRole('link', { name: 'LinkedIn' })).toHaveAttribute(
    'href',
    'https://www.linkedin.com/in/rgonzal92',
  );
  await expect(card.getByRole('button', { name: 'Keep exploring' })).toBeFocused();
  const deleted = page.waitForResponse((response) => response.request().method() === 'DELETE');
  await page.keyboard.press('Enter');

  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect((await deleted).status()).toBe(204);
  const read = myCatalogsRead(page);
  await page.goto('/dashboard');
  await read;
  await expect(listed(page, name)).toHaveCount(0);
  await expect(
    page
      .getByRole('region', { name: 'Review queue' })
      .getByRole('cell', { name: waiting, exact: true }),
  ).toBeVisible();

  // The catalog that waited goes too: the browser tests leave the author nearly twenty copies.
  await signOut(page);
  await signIn(page, 'author');
  const mine = page.getByRole('region', { name: 'My catalogs' });
  await mine.getByRole('button', { name: `Withdraw ${waiting}` }).click();
  await mine.getByRole('button', { name: `Delete ${waiting}` }).click();
  await page
    .getByRole('dialog', { name: 'Delete working copy' })
    .getByRole('button', { name: 'Delete', exact: true })
    .click();
  await expect(mine.getByRole('cell', { name: waiting, exact: true })).toHaveCount(0);
});

test('a visitor who skips the tour on the step that breaks a rule leaves no working copy behind', async ({
  page,
}) => {
  await signIn(page, 'manager');
  await walkTo(page, 'Break a rule');
  await expect(cell(page, 'SEAT_HEATED_FRONT')).toHaveClass(/tour-target/);
  const name = await tourCopy(page);
  await expectAccessible(page);

  const read = myCatalogsRead(page);
  await step(page, 'Break a rule').getByRole('button', { name: 'Skip tour' }).click();

  await expect(page).toHaveURL('/dashboard');
  await read;
  await expect(listed(page, name)).toHaveCount(0);
});

test('a visitor who declines the offer is not offered it again, and can take the tour still', async ({
  page,
}) => {
  await page.goto('/');
  await offer(page).getByRole('button', { name: 'No thanks' }).click();
  await expect(offer(page)).toHaveCount(0);

  await page.reload();
  await expect(page.getByRole('region', { name: 'Demo accounts' }).locator('tbody tr')).toHaveCount(
    3,
  );
  await expect(offer(page)).toHaveCount(0);

  await page.getByRole('main').getByRole('button', { name: 'Take the tour' }).click();
  await expect(step(page, 'What this app is for')).toBeVisible();
});

test('a step of the tour is accessible', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('main').getByRole('button', { name: 'Take the tour' }).click();
  await expect(step(page, 'What this app is for')).toBeVisible();

  await expectAccessible(page);
});

test('someone signed in takes the tour from the header past signing in, and keeps their step on a reload', async ({
  page,
}) => {
  await signIn(page, 'manager');
  await expect(page).toHaveURL('/dashboard');

  await page.getByRole('button', { name: 'Take the tour' }).click();
  await expect(step(page, 'What this app is for')).toBeVisible();
  await expect(page).toHaveURL('/');
  await page.keyboard.press('Enter');
  await expect(step(page, 'Your dashboard')).toBeVisible();

  await page.reload();
  await expect(step(page, 'Your dashboard')).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('on a narrow screen the landing page says to open it on a laptop, and offers no tour', async ({
  page,
}) => {
  const laptop = page.getByText('Open this on a laptop for a 3-minute guided tour.');
  await page.goto('/');
  await expect(offer(page)).toBeVisible();
  await expect(laptop).toBeHidden();

  await page.setViewportSize({ width: 800, height: 900 });
  await page.reload();

  await expect(laptop).toBeVisible();
  await expect(page.getByRole('main').getByRole('button', { name: 'Take the tour' })).toBeHidden();
  await expect(offer(page)).toHaveCount(0);
});

test('the review step points at the empty review queue when nothing waits for a review', async ({
  page,
}) => {
  await page.route(
    (url) => url.pathname === '/api/catalogs' && url.searchParams.get('scope') === 'review',
    (route) => route.fulfill({ json: [] }),
  );
  await signIn(page, 'admin');
  await walkTo(page, "A manager's review");

  const review = step(page, "A manager's review");
  await expect(review).toContainText(
    'Nothing is waiting for review right now. Reviews happen here.',
  );
  await expect(page).toHaveURL('/dashboard');
  await expect(page.locator('[data-tour="review-queue"]')).toHaveClass(/tour-target/);
  await review.getByRole('button', { name: 'Skip tour' }).click();
});

test('an author is told on the review step that managers review there', async ({ page }) => {
  await signIn(page, 'author');
  await walkTo(page, "A manager's review");

  const review = step(page, "A manager's review");
  await expect(review).toContainText(
    'Managers review submissions here. Sign in as manager to see it.',
  );
  await expect(page.locator('.tour-target')).toHaveCount(0);
  await review.getByRole('button', { name: 'Skip tour' }).click();
});

test('a note on how a step is built opens to its record, and starts closed on every step', async ({
  page,
}) => {
  await signIn(page, 'manager');
  await walkTo(page, 'A published catalog');

  const published = step(page, 'A published catalog');
  await expect(published.getByRole('button', { name: "How it's built" })).toHaveAttribute(
    'aria-expanded',
    'false',
  );
  await published.getByRole('button', { name: "How it's built" }).click();
  await expect(published.getByRole('link', { name: 'Read the record on GitHub' })).toHaveAttribute(
    'href',
    'https://github.com/rgonzal92/vehicle-catalog/blob/main/docs/adr/0006-the-matrix-is-one-primeng-table-with-its-own-subheaders-and-cell-editing.md',
  );

  // The review's step and the analyst's both have a note, one straight after the other.
  for (const title of [
    'A published catalog',
    'Make a working copy',
    'Break a rule',
    'Put it right',
  ]) {
    await step(page, title).getByRole('button', { name: 'Next' }).click();
  }
  const review = step(page, "A manager's review");
  await review.getByRole('button', { name: "How it's built" }).click();
  await expect(review.getByRole('button', { name: "How it's built" })).toHaveAttribute(
    'aria-expanded',
    'true',
  );
  await review.getByRole('button', { name: 'Next' }).click();
  const analyst = step(page, 'Ask the analyst');
  await expect(analyst.getByRole('button', { name: "How it's built" })).toHaveAttribute(
    'aria-expanded',
    'false',
  );
  await analyst.getByRole('button', { name: 'Skip tour' }).click();
});

test("the analyst's step is accessible with its note open", async ({ page }) => {
  await signIn(page, 'admin');
  await walkTo(page, 'Ask the analyst');

  const analyst = step(page, 'Ask the analyst');
  await analyst.getByRole('button', { name: "How it's built" }).click();
  await expect(analyst.getByRole('link', { name: 'Read the record on GitHub' })).toBeVisible();
  await expectAccessible(page);
  await analyst.getByRole('button', { name: 'Skip tour' }).click();
});
