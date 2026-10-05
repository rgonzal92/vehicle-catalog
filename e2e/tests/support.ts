import AxeBuilder from '@axe-core/playwright';
import { expect, type Locator, type Page } from '@playwright/test';

/** Signs in from the landing page as one of the login server's accounts. */
export async function signIn(page: Page, username: string): Promise<void> {
  await page.goto('/');
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[type="submit"]').click();
}

/** Signs out and waits for the landing page. */
export async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
}

/** Picks an option from a dropdown inside `scope` and waits for the list to close. */
export async function choose(scope: Locator, label: string, option: string): Promise<void> {
  const page = scope.page();

  await scope.getByLabel(label).click();
  await page.getByRole('option', { name: option, exact: true }).click();
  await expect(page.getByRole('listbox')).toBeHidden();
}

/** Fails when axe finds an accessibility violation on the settled page, naming each one. */
export async function expectAccessible(page: Page): Promise<void> {
  // A control that is still fading in or out would be judged on a color it only has for a moment.
  // One that never ends, such as a spinner, is not waited for.
  await page.evaluate(() =>
    Promise.allSettled(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getComputedTiming().endTime !== Infinity)
        .map((animation) => animation.finished),
    ),
  );
  const { violations } = await new AxeBuilder({ page }).analyze();

  expect(
    violations.map(
      (violation) => `${violation.id}: ${violation.nodes.map((node) => node.target).join(', ')}`,
    ),
  ).toEqual([]);
}
