import { expect, test } from '@playwright/test';
import { expectAccessible, signIn } from './support';

/** The login server's demo accounts, and the dashboard sections and pages each one's role can use. */
const demoAccounts = [
  {
    username: 'author',
    role: 'author',
    name: 'Demo Author',
    sections: ['My catalogs', 'Approved catalogs'],
    pages: ['Dashboard'],
  },
  {
    username: 'manager',
    role: 'manager',
    name: 'Demo Manager',
    sections: ['My catalogs', 'Approved catalogs', 'Review queue'],
    pages: ['Dashboard'],
  },
  {
    username: 'admin',
    role: 'admin',
    name: 'Demo Admin',
    sections: ['My catalogs', 'Approved catalogs', 'Review queue'],
    pages: [
      'Dashboard',
      'Vehicle lines',
      'Trims',
      'Regions',
      'Feature library',
      'Global rules',
      'Users',
      'Jobs',
    ],
  },
];

for (const { username, role, name, sections, pages } of demoAccounts) {
  test(`the ${role} demo account signs in and sees a dashboard for that role`, async ({ page }) => {
    await signIn(page, username);

    await expect(page).toHaveURL('/dashboard');
    // The name also appears in the list of Approved catalogs, as the approver.
    await expect(page.getByRole('banner').getByText(name)).toBeVisible();
    await expect(page.locator('[data-role]')).toHaveText(role);
    await expect(page.getByRole('heading', { level: 2 })).toHaveText(sections);
    await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link')).toHaveText(
      pages,
    );
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
