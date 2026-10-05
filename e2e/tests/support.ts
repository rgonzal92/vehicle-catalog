import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

/** Signs in from the landing page as one of the login server's accounts. */
export async function signIn(page: Page, username: string): Promise<void> {
  await page.goto('/');
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[type="submit"]').click();
}

/** Fails when axe finds an accessibility violation on the page, naming each one. */
export async function expectAccessible(page: Page): Promise<void> {
  const { violations } = await new AxeBuilder({ page }).analyze();

  expect(
    violations.map(
      (violation) => `${violation.id}: ${violation.nodes.map((node) => node.target).join(', ')}`,
    ),
  ).toEqual([]);
}
