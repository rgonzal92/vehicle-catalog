import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { FeatureLibraryPage } from './feature-library-page';

describe('FeatureLibraryPage', () => {
  let backend: HttpTestingController;

  const roof = {
    id: 1,
    code: 'ROOF_PANORAMIC',
    name: 'Panoramic Roof',
    description: 'A glass roof.',
    categoryCode: 'EXTERIOR',
    kind: 'FEATURE',
    status: 'ACTIVE',
    version: 3,
  };
  const tow = {
    id: 2,
    code: 'TOW_PACKAGE',
    name: 'Tow Package',
    description: '',
    categoryCode: 'PACKAGES',
    kind: 'PACKAGE',
    status: 'RETIRED',
    version: 0,
  };
  const reference = {
    vehicleTypes: [],
    categories: [
      { code: 'EXTERIOR', name: 'Exterior' },
      { code: 'PACKAGES', name: 'Packages' },
    ],
    modelYears: [],
  };

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');

  const button = (element: HTMLElement, label: string) =>
    element.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);

  const searchRequest = (): Promise<TestRequest> =>
    vi.waitFor(() =>
      backend.expectOne((request) => request.method === 'GET' && request.url === '/api/features'),
    );

  /** Renders the page with the table's first search answered: 60 features, the first two shown. */
  async function page(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(FeatureLibraryPage);
    fixture.detectChanges();
    backend.expectOne('/api/reference').flush(reference);
    (await searchRequest()).flush({ items: [roof, tow], total: 60 });
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => expect(rows(element).length).toBe(2));

    return element;
  }

  it('lists each feature with its category, kind, and status', async () => {
    const [first, second] = rows(await page());

    expect(first).toContain('ROOF_PANORAMIC');
    expect(first).toContain('Panoramic Roof');
    expect(first).toContain('A glass roof.');
    expect(first).toContain('Exterior');
    expect(first).toContain('Feature');
    expect(first).toContain('Active');
    expect(second).toContain('Packages');
    expect(second).toContain('Package');
    expect(second).toContain('Retired');
  });

  it('asks for the next page, and a new search starts over from the first', async () => {
    const element = await page();

    element.querySelector<HTMLButtonElement>('button.p-paginator-next')?.click();
    const nextPage = await searchRequest();
    expect(nextPage.request.params.get('page')).toBe('1');
    expect(nextPage.request.params.get('size')).toBe('25');
    nextPage.flush({ items: [tow], total: 60 });

    const query = element.querySelector<HTMLInputElement>('#feature-query')!;
    query.value = 'tow';
    query.dispatchEvent(new Event('input'));
    element.querySelector<HTMLFormElement>('form[role="search"]')?.requestSubmit();
    const searched = await searchRequest();
    expect(searched.request.params.get('query')).toBe('tow');
    expect(searched.request.params.get('page')).toBe('0');
    searched.flush({ items: [tow], total: 1 });
  });

  it('retires an active feature and reactivates a retired one from its row', async () => {
    const element = await page();

    button(element, 'Retire Panoramic Roof')?.click();
    backend
      .expectOne({ method: 'POST', url: '/api/features/1/retire' })
      .flush({ ...roof, status: 'RETIRED' });
    (await searchRequest()).flush({ items: [{ ...roof, status: 'RETIRED' }, tow], total: 60 });
    await vi.waitFor(() => expect(rows(element)[0]).toContain('Retired'));

    button(element, 'Reactivate Tow Package')?.click();
    backend
      .expectOne({ method: 'POST', url: '/api/features/2/reactivate' })
      .flush({ ...tow, status: 'ACTIVE' });
    (await searchRequest()).flush({ items: [roof, tow], total: 60 });
  });

  it('edits a feature from the version it showed, and shows why an edit was refused', async () => {
    const element = await page();

    button(element, 'Edit Panoramic Roof')?.click();
    const name = await vi.waitFor(() => {
      const input = document.querySelector<HTMLInputElement>('#feature-name');
      expect(input?.value).toBe('Panoramic Roof');
      return input!;
    });
    expect(document.querySelector('#feature-code')).toBeNull();
    name.value = 'Glass Roof';
    name.dispatchEvent(new Event('input'));
    name.form?.requestSubmit();

    const request = backend.expectOne({ method: 'PUT', url: '/api/features/1' });
    expect(request.request.body).toEqual({
      name: 'Glass Roof',
      description: 'A glass roof.',
      categoryCode: 'EXTERIOR',
      version: 3,
    });
    request.flush(
      { code: 'CONFLICT', detail: 'Someone else changed this after you opened it.' },
      { status: 409, statusText: 'Conflict' },
    );
    (await searchRequest()).flush({ items: [roof, tow], total: 60 });

    await vi.waitFor(() =>
      expect(document.querySelector('.p-dialog')?.textContent).toContain(
        'Someone else changed this after you opened it.',
      ),
    );
  });

  it('keeps a package in the Packages category, with no category to choose', async () => {
    const element = await page();

    button(element, 'Edit Tow Package')?.click();
    const name = await vi.waitFor(() => {
      const input = document.querySelector<HTMLInputElement>('#feature-name');
      expect(input?.value).toBe('Tow Package');
      return input!;
    });
    expect(document.querySelector('#feature-category')).toBeNull();
    name.form?.requestSubmit();

    const request = backend.expectOne({ method: 'PUT', url: '/api/features/2' });
    expect(request.request.body.categoryCode).toBe('PACKAGES');
    request.flush(tow);
    (await searchRequest()).flush({ items: [roof, tow], total: 60 });
  });
});
