import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { DocumentsPage, LOOKS_AGAIN_EVERY } from './documents-page';

describe('DocumentsPage', () => {
  let backend: HttpTestingController;
  let fixture: ComponentFixture<DocumentsPage>;
  let element: HTMLElement;

  const notes = {
    id: 7,
    title: 'Launch notes',
    vehicleLineId: 3,
    vehicleLine: 'Compact SUV',
    modelYear: 2026,
    fileName: 'launch.md',
    sizeBytes: 2048,
    uploadedBy: 'Demo Admin',
    uploadedAt: '2026-10-10T06:00:00Z',
    status: 'WAITING',
    reason: null,
    passages: 0,
  };

  const words = (part: Element | null | undefined) =>
    part?.textContent?.replace(/\s+/g, ' ').trim();
  const rows = () =>
    Array.from(element.querySelectorAll('tr[data-document]'), (row) =>
      Array.from(row.querySelectorAll('td'), words).slice(0, 6),
    );
  const button = (label: string) =>
    Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find(
      (one) => words(one) === label || one.getAttribute('aria-label') === label,
    )!;
  const refusal = () => words(element.querySelector('form p-message'));

  /** Lets what the page is waiting on happen, and the page show it. */
  const settle = async () => {
    await new Promise((done) => setTimeout(done));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /** Opens the page with these documents there. */
  async function open(documents: object[] = [notes]): Promise<void> {
    fixture = TestBed.createComponent(DocumentsPage);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    backend.expectOne('/api/documents').flush(documents);
    backend.expectOne('/api/vehicle-lines').flush([
      { id: 3, code: 'COMPACT_SUV', name: 'Compact SUV', vehicleTypeCode: 'SUV', active: true },
      { id: 4, code: 'SPORTS_COUPE', name: 'Sports Coupe', vehicleTypeCode: 'CAR', active: false },
    ]);
    backend
      .expectOne('/api/reference')
      .flush({ vehicleTypes: [], categories: [], modelYears: [2026, 2027] });
    await settle();
  }

  /** Chooses a file, as someone does in the browser's own dialog. */
  function choose(file: File): void {
    const chooser = element.querySelector<HTMLInputElement>('#document-file')!;
    Object.defineProperty(chooser, 'files', { configurable: true, value: [file] });
    chooser.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  /** Sets the form's vehicle line and model year, which a person picks from two lists. */
  function about(vehicleLineId: number, modelYear: number): void {
    const page = fixture.componentInstance as unknown as {
      form: { patchValue(value: object): void };
    };
    page.form.patchValue({ vehicleLineId, modelYear });
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('lists the documents with what each is about and how far it is', async () => {
    await open([
      notes,
      {
        ...notes,
        id: 8,
        title: 'Brochure',
        sizeBytes: 1572864,
        status: 'FAILED',
        reason: 'It holds no text.',
      },
    ]);

    expect(rows().map((row) => [row[0], row[1], row[2], row[3], row[5]])).toEqual([
      ['Launch notes', 'Compact SUV', '2026', 'launch.md, 2 kB', 'Waiting'],
      ['Brochure', 'Compact SUV', '2026', 'launch.md, 1.5 MB', 'FailedIt holds no text.'],
    ]);
  });

  it('says that there are none when there are none', async () => {
    await open([]);

    expect(words(element.querySelector('.surface-empty'))).toBe('There are no documents.');
  });

  it('uploads a file with its title, vehicle line, and model year, and lists it', async () => {
    await open([]);
    const file = new File(['# Launch notes'], 'launch-notes.md', { type: 'text/markdown' });

    choose(file);
    // Until it is given a title, a document is called by its file's name.
    expect(element.querySelector<HTMLInputElement>('#document-title')!.value).toBe('launch-notes');
    about(3, 2027);
    button('Upload').click();
    await settle();

    const sent = backend.expectOne('/api/documents');
    expect(sent.request.method).toBe('POST');
    const form = sent.request.body as FormData;
    expect(form.get('file')).toBe(file);
    expect([form.get('title'), form.get('vehicleLineId'), form.get('modelYear')]).toEqual([
      'launch-notes',
      '3',
      '2027',
    ]);
    sent.flush(notes, { status: 201, statusText: 'Created' });
    await settle();
    backend.expectOne('/api/documents').flush([notes]);
    await settle();

    expect(rows()).toHaveLength(1);
    expect(element.querySelector<HTMLInputElement>('#document-title')!.value).toBe('');
  });

  it('does not send a file of more than 2 MB, and says why', async () => {
    await open([]);

    choose(new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'large.txt'));
    about(3, 2026);
    button('Upload').click();
    await settle();

    expect(refusal()).toBe('A document is at most 2 MB.');
  });

  it('says why the backend refused an upload, and keeps what was filled in', async () => {
    await open([]);

    choose(new File(['not a PDF'], 'notes.pdf'));
    about(3, 2026);
    button('Upload').click();
    await settle();
    backend
      .expectOne('/api/documents')
      .flush(
        { code: 'VALIDATION', detail: 'This file is named as a PDF and is not one.' },
        { status: 422, statusText: 'Unprocessable Content' },
      );
    await settle();

    expect(refusal()).toBe('This file is named as a PDF and is not one.');
    expect(element.querySelector<HTMLInputElement>('#document-title')!.value).toBe('notes');
  });

  it('deletes a document once the admin has said so', async () => {
    await open();

    button('Delete the document: Launch notes').click();
    await settle();
    expect(words(document.querySelector('[data-question]'))).toBe(
      'Delete the document "Launch notes"? Its file goes with it.',
    );
    Array.from(document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find((one) => words(one) === 'Delete')!
      .click();
    await settle();

    backend.expectOne({ method: 'DELETE', url: '/api/documents/7' }).flush(null);
    await settle();
    backend.expectOne('/api/documents').flush([]);
    await settle();
    expect(rows()).toEqual([]);
  });

  it('offers to read the documents again when they could not be read', async () => {
    fixture = TestBed.createComponent(DocumentsPage);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    backend.expectOne('/api/documents').flush(null, { status: 500, statusText: 'Server Error' });
    backend.expectOne('/api/vehicle-lines').flush([]);
    backend.expectOne('/api/reference').flush({ vehicleTypes: [], categories: [], modelYears: [] });
    await settle();

    expect(words(element.querySelector('app-read-failed'))).toContain(
      'The documents could not be read.',
    );
    button('Try again').click();
    await settle();
    backend.expectOne('/api/documents').flush([notes]);
    await settle();
    expect(rows()).toHaveLength(1);
  });

  it('shows how many passages a ready document has, and has a failed one processed again', async () => {
    await open([
      { ...notes, status: 'READY', passages: 12 },
      { ...notes, id: 8, title: 'Brochure', status: 'FAILED', reason: 'The model did not answer.' },
    ]);
    expect(rows().map((row) => row[5])).toEqual([
      'Ready 12 passages',
      'FailedThe model did not answer.',
    ]);
    expect(button('Process the document again: Launch notes')).toBeUndefined();

    button('Process the document again: Brochure').click();
    await settle();

    backend
      .expectOne({ method: 'POST', url: '/api/documents/8/process' })
      .flush({ ...notes, id: 8, title: 'Brochure' });
    await settle();
    backend.expectOne('/api/documents').flush([{ ...notes, id: 8, title: 'Brochure' }]);
    await settle();
    expect(rows().map((row) => row[5])).toEqual(['Waiting']);
  });

  it('looks again while a document is waiting or being read, and no more once none is', async () => {
    vi.useFakeTimers();
    try {
      const shown = async () => {
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
      };
      fixture = TestBed.createComponent(DocumentsPage);
      element = fixture.nativeElement as HTMLElement;
      fixture.detectChanges();
      backend.expectOne('/api/documents').flush([notes]);
      backend.expectOne('/api/vehicle-lines').flush([]);
      backend
        .expectOne('/api/reference')
        .flush({ vehicleTypes: [], categories: [], modelYears: [] });
      await shown();

      await vi.advanceTimersByTimeAsync(LOOKS_AGAIN_EVERY);
      backend.expectOne('/api/documents').flush([{ ...notes, status: 'RUNNING' }]);
      await shown();
      expect(rows().map((row) => row[5])).toEqual(['Running']);

      // A look that fails is left for the next one, and the list stays as it was.
      await vi.advanceTimersByTimeAsync(LOOKS_AGAIN_EVERY);
      backend.expectOne('/api/documents').flush(null, { status: 502, statusText: 'Bad Gateway' });
      await shown();
      expect(rows().map((row) => row[5])).toEqual(['Running']);

      await vi.advanceTimersByTimeAsync(LOOKS_AGAIN_EVERY);
      backend.expectOne('/api/documents').flush([{ ...notes, status: 'READY', passages: 1 }]);
      await shown();
      expect(rows().map((row) => row[5])).toEqual(['Ready 1 passage']);

      await vi.advanceTimersByTimeAsync(LOOKS_AGAIN_EVERY * 3);
    } finally {
      vi.useRealTimers();
    }
  });
});
