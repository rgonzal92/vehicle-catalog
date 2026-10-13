import { expect } from '@playwright/test';
import { choose, expectAccessible, signIn, test, unique } from './support';

test('an admin uploads a document, sees it become ready, and deletes it', async ({ page }) => {
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
  await expect(form.getByLabel('Title')).toHaveValue('');
  // The worker reads it, and the page looks again by itself until it has.
  await expect(row).toContainText('Ready', { timeout: 30_000 });
  await expect(row).toContainText('1 passage');
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

/**
 * A PDF of one page with this line of text, written out as the format has it: its five objects,
 * and then the table of where in the file each of them starts. The line is ASCII without brackets
 * or a backslash.
 */
function pdfOf(line: string): Buffer {
  const drawing = `BT /F1 11 Tf 56 740 Td (${line}) Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R ' +
      '/Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${drawing.length} >>\nstream\n${drawing}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
  ];
  let file = '%PDF-1.4\n';
  let table = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  objects.forEach((object, index) => {
    table += `${String(file.length).padStart(10, '0')} 00000 n \n`;
    file += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const trailer = `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\n`;

  return Buffer.from(`${file}${table}${trailer}startxref\n${file.length}\n%%EOF\n`, 'ascii');
}

test("an admin uploads a PDF, which the worker reads from the image's jar, and deletes it", async ({
  page,
}) => {
  const title = unique('Towing notes');
  await signIn(page, 'admin');
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Documents' })
    .click();

  const form = page.getByRole('region', { name: 'Upload a document' });
  await form.getByLabel('File').setInputFiles({
    name: 'towing-notes.pdf',
    mimeType: 'application/pdf',
    buffer: pdfOf('The tow hitch follows in the spring.'),
  });
  await form.getByLabel('Title').fill(title);
  await choose(form, 'Vehicle line', 'Compact SUV');
  await choose(form, 'Model year', '2026');
  await form.getByRole('button', { name: 'Upload' }).click();

  // The worker runs from the backend's image here, as on the host, and starts a process for each
  // PDF from the image's one jar, through Spring Boot's launcher. The backend's own tests start
  // that process from class directories, so this is the test that fails when a PDF is no longer
  // read from the jar: its row then says "Failed" and "It could not be read as a PDF."
  const row = page.getByRole('row').filter({ hasText: title });
  await expect(row, "The worker reads a PDF from the image's jar").toContainText('Ready', {
    timeout: 30_000,
  });
  await expect(row).toContainText('1 passage');

  await row.getByRole('button', { name: `Delete the document: ${title}` }).click();
  await page
    .getByRole('dialog', { name: 'Delete document' })
    .getByRole('button', { name: 'Delete' })
    .click();
  await expect(row).toHaveCount(0);
});
