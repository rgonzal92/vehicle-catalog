import { expect } from '@playwright/test';
import { choose, expectAccessible, signIn, signOut, test, unique } from './support';

test('an author asks the analyst and reads an answer with the tool call it used', async ({
  page,
}) => {
  await signIn(page, 'author');
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Analyst' })
    .click();
  await expect(page.getByRole('heading', { level: 1, name: 'Analyst' })).toBeVisible();

  await page.getByLabel('Your question').fill('Which vehicle lines have an Approved catalog?');
  await page.getByRole('button', { name: 'Ask' }).click();

  // The stand-in for the model asks for the lineages to be listed, and then answers in words.
  const conversation = page.getByRole('region', { name: 'Conversation' });
  await expect(conversation.locator('[data-by="PERSON"]')).toContainText(
    'Which vehicle lines have an Approved catalog?',
  );
  await expect(conversation.locator('[data-by="ANALYST"]')).toContainText(
    'An answer from the stand-in, from what the tool returned.',
  );
  await expect(
    conversation.getByRole('list', { name: 'Tool calls' }).getByRole('listitem'),
  ).toHaveText(['list_lineages {}']);
  await expect(page.getByLabel('Your question')).toHaveValue('');
  await expectAccessible(page);
});

test('someone chooses whose documents are searched and reads an answer that cites one', async ({
  page,
}) => {
  const title = unique('Launch notes');
  await signIn(page, 'admin');
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Documents' })
    .click();
  const upload = page.getByRole('region', { name: 'Upload a document' });
  await upload.getByLabel('File').setInputFiles({
    name: 'launch-notes.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('The hybrid follows in the autumn. It comes to Europe first.\n'),
  });
  await upload.getByLabel('Title').fill(title);
  await choose(upload, 'Vehicle line', 'Compact SUV');
  await choose(upload, 'Model year', '2028');
  await upload.getByRole('button', { name: 'Upload' }).click();
  await expect(page.getByRole('row').filter({ hasText: title })).toContainText('Ready', {
    timeout: 30_000,
  });
  await signOut(page);

  await signIn(page, 'author');
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Analyst' })
    .click();
  const conversation = page.getByRole('region', { name: 'Conversation' });
  await choose(conversation, 'Documents of', 'Compact SUV 2028');
  await page.getByLabel('Your question').fill('When does the hybrid arrive?');
  await page.getByRole('button', { name: 'Ask' }).click();

  // The stand-in for the model searches the documents, and marks the first passage it is given.
  await expect(conversation.locator('[data-by="ANALYST"]')).toContainText(
    'The hybrid follows in the autumn [1].',
  );
  await expect(
    conversation.getByRole('list', { name: 'Tool calls' }).getByRole('listitem'),
  ).toHaveText(['search_documents {"query":"When does the hybrid follow?"}']);
  const cited = conversation.getByRole('list', { name: 'Documents cited' }).getByRole('listitem');
  await expect(cited).toHaveCount(1);
  await cited.getByText(`[1] ${title}`).click();
  await expect(cited).toContainText('The hybrid follows in the autumn. It comes to Europe first.');
  await expectAccessible(page);
});
