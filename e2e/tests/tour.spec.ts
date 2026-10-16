import { expect, type Page } from '@playwright/test';
import { expectAccessible, signIn, test } from './support';

test.use({ offersTour: true });

/** The landing page's offer of a tour. */
const offer = (page: Page) => page.locator('.p-toast-message', { hasText: 'New here?' });

/** The step of the tour with this title. */
const step = (page: Page, title: string) => page.getByRole('dialog', { name: title });

/** A cell of the working copy's matrix, by its feature's code, its trim, and its region. */
const cell = (page: Page, feature: string) => page.locator(`td[data-cell="${feature} Luxury NA"]`);

/** The name of the working copy a tour made, as the editor's heading shows it. */
const tourCopy = async (page: Page) => {
  const heading = page.getByRole('heading', { name: /^Tour copy \d\d:\d\d:\d\d$/ });
  await expect(heading).toBeVisible();
  return (await heading.textContent())!.trim();
};

/**
 * The row of My catalogs that lists a working copy, by its name. Other tests sign in as the manager
 * at the same time, so a test looks for its own copy and not for every tour's.
 */
const listed = (page: Page, name: string) =>
  page.getByRole('region', { name: 'My catalogs' }).getByRole('row', { name });

/**
 * Takes the tour from the header, signed in, to the step that breaks a rule. Answers with the name
 * of the working copy it made.
 */
async function toBreakingARule(page: Page): Promise<string> {
  await page.getByRole('button', { name: 'Take the tour' }).click();
  for (const title of [
    'What this app is for',
    'Your dashboard',
    'A published catalog',
    'Make a working copy',
  ]) {
    await expect(step(page, title).getByRole('button', { name: 'Next' })).toBeFocused();
    await page.keyboard.press('Enter');
  }
  await expect(step(page, 'Break a rule')).toBeVisible();
  await expect(cell(page, 'SEAT_HEATED_FRONT')).toHaveClass(/tour-target/);
  return tourCopy(page);
}

test('a visitor takes the tour from its offer to its last card, going on with Enter', async ({
  page,
}) => {
  await page.goto('/');
  await offer(page).getByRole('button', { name: 'Take the tour' }).click();

  const about = step(page, 'What this app is for');
  await expect(about).toContainText('a major US carmaker');
  await expect(about).toContainText('1 of 7');
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
  await expect(dashboard).toContainText('3 of 7');
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
  await expect(cell(page, 'SEAT_HEATED_FRONT')).toHaveClass(/tour-target/);
  await expect(breaking.getByRole('button', { name: 'Next' })).toBeFocused();
  await page.keyboard.press('Enter');

  // Heated rear seats need heated front seats: the rule's Error is marked on the rear seats.
  await expect(step(page, 'Put it right')).toBeVisible();
  await expect(cell(page, 'SEAT_HEATED_FRONT')).toContainText('-');
  await expect(cell(page, 'SEAT_HEATED_REAR')).toHaveClass(/matrix-error/);
  await expect(page.locator('[data-issue-counts]')).toContainText('1 Error');
  await page.keyboard.press('Enter');

  await expect(cell(page, 'SEAT_HEATED_FRONT')).toContainText('S');
  await expect(cell(page, 'SEAT_HEATED_REAR')).not.toHaveClass(/matrix-error/);

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
  await page.keyboard.press('Enter');

  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page).toHaveURL('/dashboard');
  await expect(page.getByRole('region', { name: 'My catalogs' })).toBeVisible();
  await expect(listed(page, name)).toHaveCount(0);
});

test('a visitor who skips the tour on the step that breaks a rule leaves no working copy behind', async ({
  page,
}) => {
  await signIn(page, 'manager');
  const name = await toBreakingARule(page);
  await expectAccessible(page);

  await step(page, 'Break a rule').getByRole('button', { name: 'Skip tour' }).click();

  await expect(page).toHaveURL('/dashboard');
  await expect(page.getByRole('region', { name: 'My catalogs' })).toBeVisible();
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
