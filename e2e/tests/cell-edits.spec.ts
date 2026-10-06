import { expect, test, type Page } from '@playwright/test';
import { createWorkingCopy, expectAccessible, signIn } from './support';

/** Resolves once the next save of cells has been answered, with the status it was answered with. */
const nextSave = (page: Page) =>
  page
    .waitForResponse(
      (response) => response.request().method() === 'PUT' && response.url().endsWith('/cells'),
    )
    .then((response) => response.status());

test('the owner sets cells with the dropdown and the keyboard, and each is saved at once', async ({
  page,
}) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const matrix = page.locator('app-availability-matrix');
  // The 2.0L turbo across North America (Base, Sport, Touring, Off-Road) and Europe (Base, Sport,
  // Touring), after its code.
  const turbo = matrix.getByRole('row', { name: /ENGINE_20T_I4/ }).getByRole('cell');
  await expect(turbo).toHaveText(['ENGINE_20T_I4', '-', 'A', 'S', 'S', '-', '-', 'A']);

  // With the dropdown: Base in North America becomes Standard.
  let saved = nextSave(page);
  await turbo.nth(1).click();
  await matrix.getByRole('combobox').selectOption('S');
  expect(await saved).toBe(200);
  await expectAccessible(page);

  // With the keyboard: Sport in North America becomes Not offered, and Base in Europe Available.
  saved = nextSave(page);
  await turbo.nth(2).focus();
  await page.keyboard.press('-');
  expect(await saved).toBe(200);
  saved = nextSave(page);
  await turbo.nth(5).focus();
  await page.keyboard.press('a');
  expect(await saved).toBe(200);

  // The same feature and trim is Standard in one region and Available in the other.
  await expect(turbo).toHaveText(['ENGINE_20T_I4', 'S', '-', 'S', 'S', 'A', '-', 'A']);

  // There is no save button, and nothing is lost by leaving.
  await expect(page.getByRole('button', { name: /save/i })).toHaveCount(0);
  await page.reload();
  await expect(turbo).toHaveText(['ENGINE_20T_I4', 'S', '-', 'S', 'S', 'A', '-', 'A']);
});

test('cells set in quick succession are all saved, in the order they were set', async ({
  page,
}) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const matrix = page.locator('app-availability-matrix');
  const turbo = matrix.getByRole('row', { name: /ENGINE_20T_I4/ }).getByRole('cell');
  const revisions: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'PUT' && request.url().endsWith('/cells')) {
      revisions.push(request.headers()['if-match']);
    }
  });

  // One cell is set four times over without waiting, and the arrow keys take the last to its
  // neighbour.
  await turbo.nth(1).focus();
  for (const key of ['s', 'a', '-', 's', 'ArrowRight', 's']) {
    await page.keyboard.press(key);
  }

  await expect.poll(() => revisions).toEqual(['"0"', '"1"', '"2"', '"3"', '"4"']);
  await expect(turbo).toHaveText(['ENGINE_20T_I4', 'S', 'S', 'S', 'S', '-', '-', 'A']);
  await page.reload();
  await expect(turbo).toHaveText(['ENGINE_20T_I4', 'S', 'S', 'S', 'S', '-', '-', 'A']);
});
