import { expect } from '@playwright/test';
import { choose, expectAccessible, signIn, test, unique } from './support';

test('an admin uploads a document, sees it listed as waiting, and deletes it', async ({ page }) => {
  const title = unique('Launch notes');
  await signIn(page, 'admin');
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Documents' })
    .click();
  await expect(page.getByRole('heading', { level: 1, name: 'Documents' })).toBeVisible();

  const form = page.getByRole('region', { name: 'Upload a document' });
  await form.getByLabel('File').setInputFiles({
    name: 'launch-notes.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# Launch notes\n\nThe hybrid follows in the autumn.\n'),
  });
  // Until it is given a title, a document is called by its file's name.
  await expect(form.getByLabel('Title')).toHaveValue('launch-notes');
  await form.getByLabel('Title').fill(title);
  await choose(form, 'Vehicle line', 'Compact SUV');
  await choose(form, 'Model year', '2026');
  await form.getByRole('button', { name: 'Upload' }).click();

  const row = page.getByRole('row').filter({ hasText: title });
  await expect(row).toContainText('Compact SUV');
  await expect(row).toContainText('2026');
  await expect(row).toContainText('launch-notes.md');
  await expect(row).toContainText('Waiting');
  await expect(form.getByLabel('Title')).toHaveValue('');
  await expectAccessible(page);

  // A file that is not what its name says is refused, and the page says why.
  await form.getByLabel('File').setInputFiles({
    name: 'notes.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('This is no PDF.'),
  });
  await choose(form, 'Vehicle line', 'Compact SUV');
  await choose(form, 'Model year', '2026');
  await form.getByRole('button', { name: 'Upload' }).click();
  await expect(form.getByText('This file is named as a PDF and is not one.')).toBeVisible();

  await row.getByRole('button', { name: `Delete the document: ${title}` }).click();
  const question = page.getByRole('dialog', { name: 'Delete document' });
  await expect(question).toContainText(`Delete the document "${title}"?`);
  await question.getByRole('button', { name: 'Delete' }).click();
  await expect(row).toHaveCount(0);
});
