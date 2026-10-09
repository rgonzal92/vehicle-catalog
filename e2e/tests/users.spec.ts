import { expect } from '@playwright/test';
import { choose, expectAccessible, signIn, test } from './support';

test('an admin sees the sandbox accounts and changes the role of one that is not protected', async ({
  page,
}) => {
  await signIn(page, 'admin');
  await page.getByRole('link', { name: 'Users' }).click();
  await expect(page).toHaveURL('/admin/users');
  await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();

  // The demo admin is a sandbox account, so it sees the sandbox accounts and no one else.
  await expect(page.getByRole('row')).toHaveCount(5);
  const admin = page.getByRole('row', { name: /^admin / });
  const visitor = page.getByRole('row', { name: /^visitor / });
  await expect(admin).toContainText('Admin');
  await expect(admin).not.toContainText('Never');
  await expect(visitor).toContainText('visitor@example.test');
  await expectAccessible(page);

  // A demo account is protected: its role is shown and cannot be changed.
  for (const username of ['admin', 'author', 'manager']) {
    await expect(page.getByRole('button', { name: `Change role of ${username}` })).toHaveCount(0);
  }

  await page.getByRole('button', { name: 'Change role of visitor' }).click();
  const changing = page.getByRole('dialog', { name: 'Change role' });
  await expect(changing).toContainText('Saving signs visitor out of the app.');
  await choose(changing, 'Role', 'Manager');
  await expectAccessible(page);
  await changing.getByRole('button', { name: 'Save' }).click();
  await expect(changing).toBeHidden();
  await expect(visitor).toContainText('Manager');

  await page.reload();
  await expect(visitor).toContainText('Manager');

  // Back to the role it is configured with, so that the next run starts from the same place.
  await page.getByRole('button', { name: 'Change role of visitor' }).click();
  await choose(changing, 'Role', 'Author');
  await changing.getByRole('button', { name: 'Save' }).click();
  await expect(visitor).toContainText('Author');
});

test('the users page is for admins only', async ({ page }) => {
  await signIn(page, 'manager');
  await expect(page.getByRole('link', { name: 'Users' })).toHaveCount(0);

  await page.goto('/admin/users');
  await expect(page).toHaveURL('/dashboard');
});
