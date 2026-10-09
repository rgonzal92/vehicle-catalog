import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ASKS_EVERY, ExportDialog } from './export-dialog';

@Component({
  imports: [ExportDialog],
  template: `<app-export-dialog [catalogId]="41" />`,
})
class Host {
  readonly dialog = viewChild.required(ExportDialog);
}

const waiting = { id: 7, status: 'QUEUED', fileName: 'Compact SUV 2026 v2.xlsx' };

describe('ExportDialog', () => {
  let backend: HttpTestingController;
  let host: Host;

  const dialog = () => document.querySelector<HTMLElement>('.p-dialog');
  const text = (element: Element | null | undefined) =>
    element?.textContent?.replace(/\s+/g, ' ').trim();

  /** Lets what the dialog is waiting on happen, and the dialog show it. */
  const settle = async () => {
    await vi.advanceTimersByTimeAsync(0);
    TestBed.tick();
  };

  /** Asks for the export and answers the request with this. */
  async function start(answer: object | number): Promise<void> {
    void host.dialog().start();
    const asked = backend.expectOne({ method: 'POST', url: '/api/catalogs/41/exports' });
    if (typeof answer === 'number') {
      asked.flush(
        { code: 'EXPORTS_UNAVAILABLE', detail: 'This installation has nowhere to keep it.' },
        { status: answer, statusText: 'Refused' },
      );
    } else {
      asked.flush(answer, { status: 202, statusText: 'Accepted' });
    }
    await settle();
  }

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  it('says the spreadsheet is being prepared, and offers it once it is there', async () => {
    await start(waiting);
    expect(text(dialog()?.querySelector('[data-export="waiting"]'))).toBe(
      'The spreadsheet is being prepared, which takes a moment.',
    );
    expect(dialog()?.querySelector('a')).toBeNull();

    await vi.advanceTimersByTimeAsync(ASKS_EVERY);
    backend.expectOne('/api/exports/7').flush(waiting);
    await settle();
    expect(dialog()?.querySelector('a'), 'while it still waits').toBeNull();

    await vi.advanceTimersByTimeAsync(ASKS_EVERY);
    backend.expectOne('/api/exports/7').flush({ ...waiting, status: 'READY' });
    await settle();

    expect(text(dialog()?.querySelector('[data-export="ready"]'))).toBe(
      'Compact SUV 2026 v2.xlsx is ready.',
    );
    const download = dialog()!.querySelector('a')!;
    expect(text(download)).toBe('Download');
    expect(download.getAttribute('href')).toBe('/api/exports/7/download');

    // Once the file is there, nothing more is asked.
    await vi.advanceTimersByTimeAsync(ASKS_EVERY * 3);
    backend.expectNone('/api/exports/7');
  });

  it('says so when the spreadsheet could not be built', async () => {
    await start(waiting);

    await vi.advanceTimersByTimeAsync(ASKS_EVERY);
    backend.expectOne('/api/exports/7').flush({ ...waiting, status: 'FAILED' });
    await settle();

    expect(text(dialog())).toContain('The spreadsheet could not be built. Try again in a while.');
    expect(dialog()?.querySelector('a')).toBeNull();
  });

  it('goes on asking when it could not be told how the export stands', async () => {
    await start(waiting);

    await vi.advanceTimersByTimeAsync(ASKS_EVERY);
    backend.expectOne('/api/exports/7').flush(null, { status: 503, statusText: 'Unavailable' });
    await settle();
    await vi.advanceTimersByTimeAsync(ASKS_EVERY);
    backend.expectOne('/api/exports/7').flush({ ...waiting, status: 'READY' });
    await settle();

    expect(dialog()?.querySelector('a')).not.toBeNull();
  });

  it('says why when the catalog cannot be exported, and asks nothing more', async () => {
    await start(503);

    expect(text(dialog())).toContain('This installation has nowhere to keep it.');
    await vi.advanceTimersByTimeAsync(ASKS_EVERY * 2);
    backend.expectNone((request) => request.url.startsWith('/api/exports'));
  });
});
