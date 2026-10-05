import { expect, test } from '@playwright/test';
import { expectAccessible, signIn } from './support';

const accounts = [
  { username: 'author', name: 'Demo Author', sections: ['My catalogs', 'Approved catalogs'] },
  {
    username: 'manager',
    name: 'Demo Manager',
    sections: ['My catalogs', 'Approved catalogs', 'Review queue'],
  },
  {
    username: 'admin',
    name: 'Demo Admin',
    sections: ['My catalogs', 'Approved catalogs', 'Review queue', 'Admin links'],
  },
];

for (const { username, name, sections } of accounts) {
  test(`the ${username} signs in and sees a dashboard for that role`, async ({ page }) => {
    await signIn(page, username);

    await expect(page).toHaveURL('/dashboard');
    await expect(page.getByText(name)).toBeVisible();
    await expect(page.locator('[data-role]')).toHaveText(username);
    await expect(page.getByRole('heading', { level: 2 })).toHaveText(sections);
    await expectAccessible(page);
  });
}

test('a person without a role sees only the no-role page', async ({ page }) => {
  await signIn(page, 'norole');

  await expect(page).toHaveURL('/no-role');
  await expect(page.getByRole('heading', { name: 'No role assigned' })).toBeVisible();
  await expectAccessible(page);

  await page.goto('/dashboard');
  await expect(page).toHaveURL('/no-role');
  await page.goto('/');
  await expect(page).toHaveURL('/no-role');
});
