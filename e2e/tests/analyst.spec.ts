import { expect } from '@playwright/test';
import { expectAccessible, signIn, test } from './support';

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
