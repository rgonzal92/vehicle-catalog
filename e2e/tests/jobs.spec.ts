import { execFileSync } from 'node:child_process';
import { expect } from '@playwright/test';
import { choose, expectAccessible, signIn, signOut, test } from './support';

/** Runs a statement in the database of the stack the tests run against, and answers with its output. */
function inTheDatabase(statement: string): string {
  return execFileSync(
    'npm',
    [
      'run',
      '--silent',
      'compose',
      '--',
      'exec',
      '-T',
      'db',
      'psql',
      '--username',
      'catalog',
      '--dbname',
      'catalog',
      '--tuples-only',
      '--no-align',
      '--command',
      statement,
    ],
    { encoding: 'utf8' },
  ).trim();
}

test('an admin sees a job that has failed, and has it retried', async ({ page }) => {
  // A job fails for good only after its message was delivered three times, two minutes apart. So
  // one that has failed is put into the database as the worker would have left it. It is what
  // follows the approval of a catalog that is not there, which leaves no one to tell: done at once.
  const job = inTheDatabase(
    `INSERT INTO job (type, status, dedupe_key, subject, attempts, error)
     VALUES ('AFTER_APPROVAL', 'FAILED', 'a-failed-job-' || gen_random_uuid(),
             '{"catalogId": -1}', 3, 'IllegalStateException: The owner could not be told')
     RETURNING id`,
  )
    .split('\n')
    .at(0)!;

  await signIn(page, 'admin');
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Jobs' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Jobs');
  await choose(page.getByRole('main'), 'Status', 'Failed');
  const failed = page.locator(`[data-job="${job}"]`);
  await expect(failed.getByRole('cell')).toContainText([
    'After approval',
    'Catalog -1',
    'Failed',
    '3',
    'IllegalStateException: The owner could not be told',
  ]);
  await expectAccessible(page);

  // Retried, it is no longer among the failed jobs, and is done once the worker has got to it.
  // The other tests leave jobs of their own meanwhile, newer ones, so the first page of the jobs
  // that succeeded may not reach back to this one: what became of it is read where it is kept.
  await failed.getByRole('button', { name: `Retry job ${job}` }).click();
  await expect(failed).toHaveCount(0);
  await expect
    .poll(() =>
      inTheDatabase(
        `SELECT status || ' after ' || attempts || coalesce(': ' || error, '')
         FROM job WHERE id = ${job}`,
      ),
    )
    .toBe('SUCCEEDED after 4');
});

test('an author and a manager have no way to the jobs', async ({ page }) => {
  for (const role of ['author', 'manager']) {
    await signIn(page, role);
    await expect(page).toHaveURL('/dashboard');
    await expect(
      page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Jobs' }),
    ).toHaveCount(0);

    await page.goto('/admin/jobs');
    await expect(page).toHaveURL('/dashboard');
    await signOut(page);
  }
});
