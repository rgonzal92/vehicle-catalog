import { expect, test } from '@playwright/test';

// For one run of the pipeline: a test that fails, to see that the check a merge waits for fails.
test('a trial that fails', () => {
  expect(1).toBe(2);
});
