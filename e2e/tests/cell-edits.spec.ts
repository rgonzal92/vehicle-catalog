import { expect, test } from '@playwright/test';
import { createWorkingCopy, expectAccessible, nextSave, onSave, signIn, turboOf } from './support';

test('the owner sets cells with the dropdown and the keyboard, and each is saved at once', async ({
  page,
}) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  const matrix = page.locator('app-availability-matrix');
  const turbo = turboOf(page);
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

  // Setting a cell to what it already is saves nothing, and the matrix says so.
  const sent: string[] = [];
  onSave(page, (request) => sent.push(request.method()));
  await turbo.nth(1).focus();
  await page.keyboard.press('s');
  await expect(
    matrix.getByText(
      'No new changes: 2.0L Turbo I4 Engine, Base in North America is already Standard.',
    ),
  ).toBeVisible();
  expect(sent).toEqual([]);
  await expectAccessible(page);

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
  const turbo = turboOf(page);
  const revisions: string[] = [];
  onSave(page, (request) => revisions.push(request.headers()['if-match']));

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
