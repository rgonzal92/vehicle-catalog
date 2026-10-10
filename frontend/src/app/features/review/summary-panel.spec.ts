import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ASKS_EVERY, SummaryPanel } from './summary-panel';

describe('SummaryPanel', () => {
  let backend: HttpTestingController;
  let element: HTMLElement;

  const said = () =>
    element.querySelector('[data-summary]')?.textContent?.replace(/\s+/g, ' ').trim();

  /** Lets what the panel is waiting on happen, and the panel show it. */
  const settle = async () => {
    await vi.advanceTimersByTimeAsync(0);
    TestBed.tick();
  };

  /** Answers the panel's asking for the summary. */
  async function answer(summary: object | number): Promise<void> {
    const asked = backend.expectOne('/api/catalogs/41/summary');
    if (typeof summary === 'number') {
      asked.flush({ code: 'NOT_FOUND' }, { status: summary, statusText: 'Refused' });
    } else {
      asked.flush(summary);
    }
    await settle();
  }

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(SummaryPanel);
    fixture.componentRef.setInput('catalogId', 41);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  it('says that the summary is being written, and shows it once it is there', async () => {
    await answer({ status: 'PENDING', headline: null, bullets: [], reason: null });
    expect(said()).toBe('The summary is being written.');

    await vi.advanceTimersByTimeAsync(ASKS_EVERY);
    await answer({
      status: 'READY',
      headline: 'One engine becomes standard',
      bullets: ['The 2.0L turbo becomes Standard on Sport.', 'Nothing else changes.'],
      reason: null,
    });

    expect(element.querySelector('[data-summary] p')?.textContent).toBe(
      'One engine becomes standard',
    );
    expect(Array.from(element.querySelectorAll('li'), (bullet) => bullet.textContent)).toEqual([
      'The 2.0L turbo becomes Standard on Sport.',
      'Nothing else changes.',
    ]);
    expect(element.textContent).toContain('Written by a language model');

    // Once it is there, it is asked for no more.
    await vi.advanceTimersByTimeAsync(ASKS_EVERY * 3);
  });

  it('says why when there is no summary to be had', async () => {
    await answer({
      status: 'UNAVAILABLE',
      headline: null,
      bullets: [],
      reason: 'The model did not answer.',
    });

    expect(said()).toBe('There is no summary. The model did not answer.');
    await vi.advanceTimersByTimeAsync(ASKS_EVERY * 3);
  });

  it('says that there is none when the catalog has none', async () => {
    await answer(404);

    expect(said()).toBe('There is no summary.');
  });

  it('goes on asking when it could not be told how a summary that is being written stands', async () => {
    await answer({ status: 'PENDING', headline: null, bullets: [], reason: null });
    await vi.advanceTimersByTimeAsync(ASKS_EVERY);
    await answer(503);
    expect(said()).toBe('The summary is being written.');

    await vi.advanceTimersByTimeAsync(ASKS_EVERY);
    await answer({ status: 'READY', headline: 'It is there', bullets: [], reason: null });

    expect(said()).toBe('It is there');
  });
});
