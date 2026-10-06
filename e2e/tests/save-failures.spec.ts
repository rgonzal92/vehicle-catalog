import { expect, test, type Page } from '@playwright/test';
import { createWorkingCopy, expectAccessible, signIn } from './support';

/** The cells of the 2.0L turbo's row, after its code: four offerings in North America, three in Europe. */
const turboOf = (page: Page) =>
  page
    .locator('app-availability-matrix')
    .getByRole('row', { name: /ENGINE_20T_I4/ })
    .getByRole('cell');

/** Resolves once the next save of cells has been answered, with the status it was answered with. */
const nextSave = (page: Page) =>
  page
    .waitForResponse(
      (response) => response.request().method() === 'PUT' && response.url().endsWith('/cells'),
    )
    .then((response) => response.status());

/** Counts the saves of cells the page sends from now on. */
function countSaves(page: Page): () => number {
  let saves = 0;
  page.on('request', (request) => {
    if (request.method() === 'PUT' && request.url().endsWith('/cells')) {
      saves++;
    }
  });
  return () => saves;
}

test('a tab that is behind another one stops, asks for a reload, and goes on after it', async ({
  page,
  context,
}) => {
  await signIn(page, 'manager');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const second = await context.newPage();
  await second.goto(page.url());
  await expect(turboOf(second)).toHaveText(['ENGINE_20T_I4', '-', 'A', 'S', 'S', '-', '-', 'A']);

  // The first tab saves a cell.
  let saved = nextSave(page);
  await turboOf(page).nth(1).focus();
  await page.keyboard.press('s');
  expect(await saved).toBe(200);

  // The second tab, which has not seen that, is refused: its cell goes back to what it was, with
  // the reason, and it asks for a reload.
  const savesOfSecond = countSaves(second);
  saved = nextSave(second);
  await turboOf(second).nth(2).focus();
  await second.keyboard.press('-');
  expect(await saved).toBe(412);
  await expect(second.getByRole('alert')).toContainText(
    'This catalog was changed somewhere else after you opened it',
  );
  await expect(turboOf(second).nth(2)).toHaveText('A (not saved)');
  await expect(turboOf(second).nth(2)).toHaveAttribute('title', /^Not saved: /);
  await expectAccessible(second);

  // Until then it takes no change and sends nothing.
  await turboOf(second).nth(3).click();
  await expect(second.locator('app-availability-matrix').getByRole('combobox')).toHaveCount(0);
  await second.keyboard.press('a');
  await expect(turboOf(second).nth(3)).toHaveText('S');
  expect(savesOfSecond()).toBe(1);

  // After the reload it shows the first tab's change and edits again.
  await second.getByRole('button', { name: 'Reload' }).click();
  await expect(turboOf(second)).toHaveText(['ENGINE_20T_I4', 'S', 'A', 'S', 'S', '-', '-', 'A']);
  await expect(second.getByRole('alert')).toHaveCount(0);
  saved = nextSave(second);
  await turboOf(second).nth(2).focus();
  await second.keyboard.press('-');
  expect(await saved).toBe(200);
  await expect(turboOf(second)).toHaveText(['ENGINE_20T_I4', 'S', '-', 'S', 'S', '-', '-', 'A']);
});

test('a refused save puts the cell back with the reason, and editing goes on', async ({ page }) => {
  await signIn(page, 'manager');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const turbo = turboOf(page);
  await expect(turbo.nth(1)).toHaveText('-');

  // The backend's answer to the next save is replaced by a refusal.
  await page.route(
    '**/cells',
    (route) =>
      route.fulfill({
        status: 422,
        contentType: 'application/problem+json',
        body: JSON.stringify({ code: 'VALIDATION', detail: 'This cell cannot be set.' }),
      }),
    { times: 1 },
  );
  await turbo.nth(1).focus();
  await page.keyboard.press('s');

  await expect(turbo.nth(1)).toHaveText('- (not saved)');
  await expect(turbo.nth(1)).toHaveAttribute('title', 'Not saved: This cell cannot be set.');
  await expect(page.getByText('This cell cannot be set.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Reload' })).toHaveCount(0);

  const saved = nextSave(page);
  await page.keyboard.press('a');
  expect(await saved).toBe(200);
  await expect(turbo.nth(1)).toHaveText('A');
});

test('a save that gets no answer stops the editor and is not sent again', async ({ page }) => {
  await signIn(page, 'manager');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const turbo = turboOf(page);
  const saves = countSaves(page);

  await page.route('**/cells', (route) => route.abort(), { times: 1 });
  await turbo.nth(1).focus();
  await page.keyboard.press('s');

  await expect(page.getByRole('alert').filter({ hasText: 'Reload' })).toContainText(
    'No answer says whether your last change was saved.',
  );
  await expect(turbo.nth(1)).toHaveText('- (not saved)');
  await page.keyboard.press('a');
  await expect(turbo.nth(1)).toHaveText('- (not saved)');
  expect(saves()).toBe(1);

  // Reloading shows what was saved, which is nothing, and lets editing go on.
  await page.getByRole('button', { name: 'Reload' }).click();
  await expect(turbo.nth(1)).toHaveText('-');
  const saved = nextSave(page);
  await turbo.nth(1).focus();
  await page.keyboard.press('s');
  expect(await saved).toBe(200);
});
