import AxeBuilder from '@axe-core/playwright';
import { expect, type Locator, type Page, type Request } from '@playwright/test';

/** Signs in from the landing page as one of the login server's accounts. */
export async function signIn(page: Page, username: string): Promise<void> {
  await page.goto('/');
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[type="submit"]').click();
}

/** Signs out and waits for the landing page. */
export async function signOut(page: Page): Promise<void> {
  // Sign out is in the person's account, which the frame around a page holds. The page for a
  // person without a role has no frame and has Sign out on it.
  if (!page.url().endsWith('/no-role')) {
    await page.getByRole('button', { name: /^Account: / }).click();
  }
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

/**
 * Waits for the controls that are fading in, fading out, or changing color, which would otherwise
 * be judged on a color they only have for a moment. One that never ends, such as a spinner, is
 * not waited for.
 */
const settled = (page: Page) =>
  page.evaluate(() =>
    Promise.allSettled(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getComputedTiming().endTime !== Infinity)
        .map((animation) => animation.finished),
    ),
  );

/**
 * Fails when axe finds an accessibility violation on the settled page, naming each one. The page
 * is checked in light, and then for contrast in dark, which differs from light in its colors alone.
 */
export async function expectAccessible(page: Page): Promise<void> {
  await settled(page);
  const light = await new AxeBuilder({ page }).analyze();

  await page.emulateMedia({ colorScheme: 'dark' });
  await expect(page.locator('html')).toHaveClass(/app-dark/);
  await settled(page);
  const dark = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
  await page.emulateMedia({ colorScheme: 'light' });
  await expect(page.locator('html')).not.toHaveClass(/app-dark/);

  const named = (scheme: string, { violations }: typeof light) =>
    violations.map(
      (violation) =>
        `${scheme}, ${violation.id}: ${violation.nodes.map((node) => node.target).join(', ')}`,
    );
  expect([...named('light', light), ...named('dark', dark)]).toEqual([]);
}

/** A name no other test and no earlier run uses, since an owner's working copy names are unique. */
export const unique = (name: string) => `${name} ${Date.now()}-${Math.floor(Math.random() * 1000)}`;

/** Opens the new catalog dialog from the dashboard and sets its vehicle line and model year. */
export async function startNewCatalog(
  page: Page,
  vehicleType: string,
  vehicleLine: string,
  modelYear: string,
): Promise<Locator> {
  await page.getByRole('button', { name: 'New catalog' }).click();
  const dialog = page.getByRole('dialog', { name: 'New catalog' });
  await choose(dialog, 'Vehicle type', vehicleType);
  await choose(dialog, 'Vehicle line', vehicleLine);
  await choose(dialog, 'Model year', modelYear);

  return dialog;
}

/**
 * Creates a working copy from the dashboard and waits for it to open in the catalog editor. Answers
 * with its name.
 */
export async function createWorkingCopy(
  page: Page,
  vehicleType: string,
  vehicleLine: string,
  modelYear: string,
): Promise<string> {
  const name = unique(`${vehicleLine} ${modelYear}`);
  const dialog = await startNewCatalog(page, vehicleType, vehicleLine, modelYear);
  await expect(dialog.getByText(/^Starts /)).toBeVisible();
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByRole('heading', { name })).toBeVisible();

  return name;
}

/**
 * The cells of the 2.0L turbo's row in the matrix: its code, then four offerings in North America
 * (Base, Sport, Touring, Off-Road) and three in Europe (Base, Sport, Touring) for Compact SUV 2026.
 */
export const turboOf = (page: Page) =>
  page
    .locator('app-availability-matrix')
    .getByRole('row', { name: /ENGINE_20T_I4/ })
    .getByRole('cell');

/** Whether the request is a save of cells. */
const savesCells = (request: Request) =>
  request.method() === 'PUT' && request.url().endsWith('/cells');

/** Resolves once the next save of cells has been answered, with the status it was answered with. */
export const nextSave = (page: Page) =>
  page
    .waitForResponse((response) => savesCells(response.request()))
    .then((response) => response.status());

/** Calls back with every save of cells the page sends from now on. */
export function onSave(page: Page, heard: (request: Request) => void): void {
  page.on('request', (request) => {
    if (savesCells(request)) {
      heard(request);
    }
  });
}
