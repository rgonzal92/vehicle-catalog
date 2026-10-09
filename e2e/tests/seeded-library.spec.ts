import { expect } from '@playwright/test';
import { signIn, test } from './support';

test('the library starts with seeded trims, regions, vehicle lines, and features', async ({
  page,
}) => {
  const cell = (text: string) => page.getByRole('cell', { name: text, exact: true });

  await signIn(page, 'admin');
  await page.getByRole('link', { name: 'Trims' }).click();
  for (const trim of ['Base', 'Sport', 'Touring', 'Luxury', 'Off-Road', 'Performance']) {
    await expect(cell(trim)).toBeVisible();
  }

  await page.goto('/admin/regions');
  for (const region of ['North America', 'South America', 'Europe', 'Asia']) {
    await expect(cell(region)).toBeVisible();
  }

  await page.goto('/admin/vehicle-lines');
  for (const line of ['Sedan', 'Sports Coupe', 'Compact SUV', 'Full-Size SUV', 'Pickup Truck']) {
    await expect(cell(line)).toBeVisible();
  }

  await page.goto('/admin/features');
  // More features than one page holds.
  await expect(page.getByRole('button', { name: 'Next Page' })).toBeEnabled();
  const filters = page.getByRole('search');
  for (const [name, category] of [
    ['Panoramic Roof', 'Exterior'],
    ['Tow Package', 'Packages'],
    ['Heavy-Duty Cooling', 'Thermal'],
  ]) {
    await filters.getByLabel('Code or name').fill(name);
    await filters.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByRole('row', { name: new RegExp(name) }).first()).toContainText(category);
  }
});
