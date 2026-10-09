import { expect, test } from '@playwright/test';
import { choose, createWorkingCopy, expectAccessible, signIn } from './support';

test('an owner adds, changes, and deletes a rule of a working copy', async ({ page }) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  await page.getByRole('tab', { name: 'Rules' }).click();
  const rules = page.getByRole('tabpanel', { name: 'Rules' });
  await expect(
    rules.getByRole('row', {
      name: /^Tow Package Requires Heavy-Duty Cooling Every trim Every region Global$/,
    }),
  ).toBeVisible();
  // The working copy starts with the rules of the Approved version it was copied from.
  await expect(
    rules.getByRole('row', {
      name: /^AM\/FM Radio Requires Digital Radio Every trim Europe Catalog/,
    }),
  ).toBeVisible();
  await expectAccessible(page);

  await rules.getByRole('button', { name: 'Add rule' }).click();
  const adding = page.getByRole('dialog', { name: 'Add rule' });
  await expect(adding.getByRole('button', { name: 'Save' })).toBeDisabled();
  await choose(adding, 'Source', 'Leather Seats');
  // The list of several is opened by its box: its labelled part is there for the keyboard alone.
  await adding.getByText('Choose feature rows').click();
  await page.getByRole('option', { name: 'Premium Audio', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await adding.getByRole('checkbox', { name: 'The rule holds on every trim' }).uncheck();
  await adding.getByText('Choose trims').click();
  await page.getByRole('option', { name: 'Sport', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await expectAccessible(page);
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding).toBeHidden();
  const added = rules.getByRole('row', {
    name: /^Leather Seats Requires Premium Audio Sport Every region Catalog/,
  });
  await expect(added).toBeVisible();

  await added.getByRole('button', { name: /^Edit the rule/ }).click();
  const editing = page.getByRole('dialog', { name: 'Edit rule' });
  await expect(editing.getByText("A rule's kind cannot be changed.")).toBeVisible();
  await editing.getByRole('checkbox', { name: 'The rule holds in every region' }).uncheck();
  await editing.getByText('Choose regions').click();
  await page.getByRole('option', { name: 'Europe', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await editing.getByRole('button', { name: 'Save' }).click();
  await expect(editing).toBeHidden();
  const changed = rules.getByRole('row', {
    name: /^Leather Seats Requires Premium Audio Sport Europe Catalog/,
  });
  await expect(changed).toBeVisible();

  await changed.getByRole('button', { name: /^Delete the rule/ }).click();
  const asking = page.getByRole('dialog', { name: 'Delete rule' });
  await expect(asking).toContainText(
    'Delete the rule that Leather Seats requires Premium Audio (on Sport; in Europe)?',
  );
  await asking.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(asking).toBeHidden();
  await expect(rules.getByRole('row', { name: /^Leather Seats Requires/ })).toHaveCount(0);

  await page.getByRole('tab', { name: 'History' }).click();
  const history = page.getByRole('tabpanel', { name: 'History' });
  await expect(
    history.getByRole('row', {
      name: /Rule added Leather Seats requires Premium Audio \(on Sport\)$/,
    }),
  ).toBeVisible();
  await expect(
    history.getByRole('row', {
      name: /Rule updated was Leather Seats requires Premium Audio \(on Sport\), now Leather Seats requires Premium Audio \(on Sport; in Europe\)$/,
    }),
  ).toBeVisible();
  await expect(history.getByRole('row', { name: /Rule removed/ })).toBeVisible();
});

test('an exclusion of a working copy is kept, shown, and deleted as a pair', async ({ page }) => {
  await signIn(page, 'author');
  await createWorkingCopy(page, 'SUV', 'Compact SUV', '2026');
  await page.getByRole('tab', { name: 'Rules' }).click();
  const rules = page.getByRole('tabpanel', { name: 'Rules' });

  await rules.getByRole('button', { name: 'Add rule' }).click();
  const adding = page.getByRole('dialog', { name: 'Add rule' });
  await choose(adding, 'Kind', 'Excludes');
  await choose(adding, 'Source', 'Manual Transmission');
  await adding.getByText('Choose feature rows').click();
  await page.getByRole('option', { name: 'Leather Seats', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await adding.getByRole('button', { name: 'Save' }).click();
  await expect(adding).toBeHidden();
  // The library's own exclusions are listed too, so the catalog's are told by their origin: the
  // pair the copy started with, and the new one.
  await expect(rules.getByRole('row', { name: /Excludes.*Catalog/ })).toHaveCount(4);

  await rules
    .getByRole('button', {
      name: 'Show the pair of the rule: Manual Transmission excludes Leather Seats',
    })
    .click();
  await expect(rules.locator('tr[data-shown-pair]')).toHaveCount(2);
  await expectAccessible(page);

  await rules
    .getByRole('button', {
      name: 'Delete the rule: Leather Seats excludes Manual Transmission',
    })
    .click();
  const asking = page.getByRole('dialog', { name: 'Delete rule' });
  await expect(asking).toContainText(
    'Its pair, Manual Transmission excludes Leather Seats, goes with it.',
  );
  await asking.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(asking).toBeHidden();
  await expect(rules.getByRole('row', { name: /Excludes.*Catalog/ })).toHaveCount(2);
});

test('an Approved version shows its rules, and nobody changes them', async ({ page }) => {
  await signIn(page, 'author');
  const lineages = (await (await page.request.get('/api/lineages')).json()) as {
    vehicleLine: string;
    modelYear: number;
    catalogId: number;
  }[];
  const sedan = lineages.find(
    ({ vehicleLine, modelYear }) => vehicleLine === 'Sedan' && modelYear === 2027,
  )!;

  await page.goto(`/catalogs/${sedan.catalogId}`);
  await page.getByRole('tab', { name: 'Rules' }).click();
  const rules = page.getByRole('tabpanel', { name: 'Rules' });

  await expect(
    rules.getByRole('row', {
      name: /^Lane Keep Assist Requires Driver Monitoring Every trim Europe Catalog$/,
    }),
  ).toBeVisible();
  await expect(
    rules.getByRole('row', {
      name: /^8-Speed Automatic Transmission Requires Steering-Wheel Paddle Shifters Sport Every region Catalog$/,
    }),
  ).toBeVisible();
  await expect(rules.getByRole('button', { name: 'Add rule' })).toHaveCount(0);
  await expect(rules.getByRole('button', { name: /^Edit the rule/ })).toHaveCount(0);
});
