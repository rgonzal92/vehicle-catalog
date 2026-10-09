import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { Job, subjectInWords, typeInWords } from './jobs';
import { JobsPage } from './jobs-page';

const failed: Job = {
  id: 12,
  type: 'AFTER_APPROVAL',
  subject: { catalogId: 41 },
  status: 'FAILED',
  attempts: 3,
  error: 'IllegalStateException: The owner could not be told',
  createdAt: '2026-10-09T10:00:00Z',
  updatedAt: '2026-10-09T10:06:00Z',
};
const done: Job = {
  ...failed,
  id: 11,
  type: 'RECHECK_APPROVED',
  subject: { catalogId: 40, libraryRevision: 7 },
  status: 'SUCCEEDED',
  attempts: 1,
  error: null,
};

describe('JobsPage', () => {
  let backend: HttpTestingController;
  let messages: MessageService;

  const jobsRequest = () =>
    vi.waitFor(() => backend.expectOne((request) => request.url === '/api/jobs'));

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td'))
        .slice(0, 5)
        .map((cell) => cell.textContent?.trim()),
    );

  const button = (element: HTMLElement, label: string) =>
    Array.from(element.querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    );

  /** Renders the page and answers its first read with these jobs. */
  async function page(items: Job[]): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService],
    });
    backend = TestBed.inject(HttpTestingController);
    messages = TestBed.inject(MessageService);
    const fixture = TestBed.createComponent(JobsPage);
    fixture.detectChanges();
    const request = await jobsRequest();
    expect(request.request.params.has('status')).toBe(false);
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('25');
    request.flush({ items, total: items.length });
    const element = fixture.nativeElement as HTMLElement;
    // The table shows a row for each job, or one that says there are none.
    await vi.waitFor(() => expect(element.querySelector('tbody tr')).not.toBeNull());

    return element;
  }

  afterEach(() => backend.verify());

  it('lists each job with what it is, what it is about, how it stands, and its last failure', async () => {
    const element = await page([failed, done]);

    expect(rows(element)).toEqual([
      [
        'After approval',
        'Catalog 41',
        'Failed',
        '3',
        'IllegalStateException: The owner could not be told',
      ],
      ['Recheck approved', 'Catalog 40, library revision 7', 'Succeeded', '1', ''],
    ]);
    expect(
      Array.from(element.querySelectorAll('tbody tr')).map(
        (row) => row.querySelector('button')?.getAttribute('aria-label') ?? null,
      ),
      'only a failed job is retried',
    ).toEqual(['Retry job 12', null]);
  });

  it('has a failed job retried and reads the list again', async () => {
    const element = await page([failed]);

    button(element, 'Retry')!.click();
    backend
      .expectOne({ method: 'POST', url: '/api/jobs/12/retry' })
      .flush({ ...failed, status: 'QUEUED' });
    (await jobsRequest()).flush({ items: [{ ...failed, status: 'QUEUED' }], total: 1 });

    await vi.waitFor(() => expect(rows(element)[0][2]).toBe('Queued'));
    expect(button(element, 'Retry')).toBeUndefined();
  });

  it('says why a job was not retried, and shows how it stands by now', async () => {
    const element = await page([failed]);
    const shown = vi.spyOn(messages, 'add');

    button(element, 'Retry')!.click();
    backend
      .expectOne({ method: 'POST', url: '/api/jobs/12/retry' })
      .flush(
        { code: 'NOT_FAILED', detail: 'Only a job that has failed can be retried.' },
        { status: 409, statusText: 'Conflict' },
      );
    (await jobsRequest()).flush({ items: [{ ...failed, status: 'SUCCEEDED' }], total: 1 });

    await vi.waitFor(() => expect(rows(element)[0][2]).toBe('Succeeded'));
    expect(shown).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'The job was not retried',
      detail: 'Only a job that has failed can be retried.',
    });
  });

  it('reads the list again when asked, as a job that waited may be done by then', async () => {
    const element = await page([{ ...failed, status: 'QUEUED' }]);

    button(element, 'Refresh')!.click();
    (await jobsRequest()).flush({ items: [{ ...failed, status: 'SUCCEEDED' }], total: 1 });

    await vi.waitFor(() => expect(rows(element)[0][2]).toBe('Succeeded'));
  });

  it('says so when there are no jobs, and when they cannot be read', async () => {
    const element = await page([]);
    expect(element.querySelector('tbody')?.textContent).toContain('There are no jobs.');

    button(element, 'Refresh')!.click();
    (await jobsRequest()).flush(null, { status: 503, statusText: 'Unavailable' });

    await vi.waitFor(() => expect(element.textContent).toContain('The jobs could not be read.'));
  });
});

describe('a job in words', () => {
  it('says its type as words', () => {
    expect(typeInWords('AFTER_APPROVAL')).toBe('After approval');
    expect(typeInWords('EXPORT')).toBe('Export');
  });

  it('says what it is about by the names the app has for it, and any other as it is', () => {
    expect(subjectInWords({ catalogId: 41 })).toBe('Catalog 41');
    expect(subjectInWords({ catalogId: 40, libraryRevision: 7 })).toBe(
      'Catalog 40, library revision 7',
    );
    expect(subjectInWords({ exportId: 'a1b2' })).toBe('exportId a1b2');
  });
});
