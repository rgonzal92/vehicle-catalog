import { expect, test } from '@playwright/test';
import { createWorkingCopy, expectAccessible, nextSave, signIn, turboOf } from './support';

test('the History tab lists every change, newest first, with who made it and the value before', async ({
  page,
}) => {
  await signIn(page, 'admin');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');

  // A working copy takes no history from the Approved version it was copied from.
  await page.getByRole('tab', { name: 'History' }).click();
  const history = page.locator('app-history-tab');
  await expect(history.getByText('No changes have been made to this catalog.')).toBeVisible();
  await expectAccessible(page);

  // Base in North America becomes Standard, then Sport there becomes Not offered.
  await page.getByRole('tab', { name: 'Features' }).click();
  for (const [offering, key] of [
    [1, 's'],
    [2, '-'],
  ] as const) {
    const saved = nextSave(page);
    await turboOf(page).nth(offering).focus();
    await page.keyboard.press(key);
    expect(await saved).toBe(200);
  }

  await page.getByRole('tab', { name: 'History' }).click();
  const changes = history.locator('tbody').getByRole('row');
  await expect(changes).toHaveCount(2);
  await expect(changes.nth(0).getByRole('cell')).toHaveText([
    /20\d\d/,
    'Demo Admin',
    'Cell set',
    '2.0L Turbo I4 Engine (ENGINE_20T_I4), Sport in North America: was Available, now Not offered',
  ]);
  await expect(changes.nth(1).getByRole('cell')).toHaveText([
    /20\d\d/,
    'Demo Admin',
    'Cell set',
    '2.0L Turbo I4 Engine (ENGINE_20T_I4), Base in North America: was Not offered, now Standard',
  ]);
  await expectAccessible(page);
});
