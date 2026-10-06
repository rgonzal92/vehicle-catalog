import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Catalog, CatalogEdit } from '../../core/catalogs';
import { ManageOfferingsDialog } from './manage-offerings-dialog';

/** Base and Sport in North America and Europe; Sport is not sold in Europe. Three cells in all. */
const catalog = {
  snapshot: {
    catalogId: 41,
    trims: [
      { id: 1, name: 'Base', sortOrder: 1 },
      { id: 2, name: 'Sport', sortOrder: 2 },
    ],
    regions: [
      { code: 'NA', name: 'North America' },
      { code: 'EU', name: 'Europe' },
    ],
    offerings: [
      { trimId: 1, regionCode: 'NA' },
      { trimId: 1, regionCode: 'EU' },
      { trimId: 2, regionCode: 'NA' },
    ],
    cells: [
      { featureId: 7, trimId: 1, regionCode: 'NA', availability: 'S' },
      { featureId: 7, trimId: 1, regionCode: 'EU', availability: 'A' },
      { featureId: 8, trimId: 1, regionCode: 'EU', availability: 'S' },
    ],
  },
} as unknown as Catalog;

@Component({
  imports: [ManageOfferingsDialog],
  template: `<app-manage-offerings-dialog
    [catalog]="catalog"
    [run]="run"
    [editable]="editable()"
  />`,
})
class Host {
  readonly dialog = viewChild.required(ManageOfferingsDialog);
  readonly catalog = catalog;
  readonly editable = signal(true);

  /** The edits the dialog asked to be sent. Each is sent as an edit of revision 4. */
  readonly sent: Promise<number>[] = [];
  refusal: unknown = null;

  readonly run = async (edit: CatalogEdit): Promise<void> => {
    this.sent.push(edit(4));
    if (this.refusal) {
      throw this.refusal;
    }
  };
}

describe('ManageOfferingsDialog', () => {
  let backend: HttpTestingController;
  let fixture: ComponentFixture<Host>;

  const libraryTrims = [
    { id: 1, name: 'Base', sortOrder: 1, active: true },
    { id: 2, name: 'Sport', sortOrder: 2, active: false },
    { id: 3, name: 'Touring', sortOrder: 3, active: true },
    { id: 4, name: 'Limited', sortOrder: 4, active: false },
  ];
  const libraryRegions = [
    { code: 'NA', name: 'North America', sortOrder: 1, active: true },
    { code: 'EU', name: 'Europe', sortOrder: 2, active: false },
    { code: 'ASIA', name: 'Asia', sortOrder: 3, active: true },
    { code: 'SA', name: 'South America', sortOrder: 4, active: false },
  ];

  const dialog = () => document.querySelector<HTMLElement>('.p-dialog')!;

  const box = (label: string) =>
    dialog().querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;

  const button = (label: string) =>
    Array.from(dialog().querySelectorAll('button')).find(
      (candidate) =>
        candidate.getAttribute('aria-label') === label || candidate.textContent?.trim() === label,
    )!;

  const question = () => dialog().querySelector('[data-question]')?.textContent?.trim();

  beforeEach(async () => {
    // A dropdown asks how wide the screen is before it opens, which the test page cannot say.
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();

    void fixture.componentInstance.dialog().open();
    backend.expectOne('/api/trims').flush(libraryTrims);
    backend.expectOne('/api/regions').flush(libraryRegions);
    await vi.waitFor(() => expect(box('Base is sold in Europe')).not.toBeNull());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    backend.verify();
  });

  it('shows where each trim is sold and marks what the library has deactivated', () => {
    expect(box('Base is sold in North America').checked).toBe(true);
    expect(box('Base is sold in Europe').checked).toBe(true);
    expect(box('Sport is sold in North America').checked).toBe(true);
    expect(box('Sport is sold in Europe').checked).toBe(false);

    const rows = Array.from(dialog().querySelectorAll('tbody tr')).map((row) =>
      row.querySelector('th')?.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(rows).toEqual(['Base', 'Sport Inactive']);
    const regions = Array.from(dialog().querySelectorAll('thead th'))
      .slice(1, 3)
      .map((heading) => heading.textContent?.replace(/\s+/g, ' ').trim());
    expect(regions).toEqual(['North America Remove', 'Europe InactiveRemove']);
  });

  it('saves a ticked box at once, as the regions the trim is then sold in', async () => {
    box('Sport is sold in Europe').click();

    const request = await vi.waitFor(() =>
      backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/trims/2/regions' }),
    );
    expect(request.request.headers.get('If-Match')).toBe('"4"');
    expect(request.request.body).toEqual({ regionCodes: ['NA', 'EU'] });
    request.flush({ revision: 5 });
    expect(question()).toBeUndefined();
  });

  it('says how many cells go with an offering before it removes it', async () => {
    box('Base is sold in Europe').click();

    await vi.waitFor(() =>
      expect(question()).toBe(
        'Base will no longer be sold in Europe. 2 cells go with this offering.',
      ),
    );
    backend.expectNone((request) => request.method !== 'GET');

    // Nothing else is taken while the question stands, and keeping gives the box the focus back.
    expect(box('Sport is sold in Europe').disabled).toBe(true);
    expect(dialog().querySelector('[role="alert"]')?.textContent).toContain('2 cells go');
    button('Keep').click();
    await vi.waitFor(() => expect(question()).toBeUndefined());
    expect(box('Base is sold in Europe').checked).toBe(true);
    await vi.waitFor(() => expect(document.activeElement).toBe(box('Base is sold in Europe')));
    expect(fixture.componentInstance.sent).toEqual([]);

    box('Base is sold in Europe').click();
    await vi.waitFor(() => expect(question()).toBeDefined());
    button('Remove').click();
    const request = await vi.waitFor(() =>
      backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/trims/1/regions' }),
    );
    expect(request.request.body).toEqual({ regionCodes: ['NA'] });
    request.flush({ revision: 5 });
  });

  it('says what goes with a trim or a region before it removes it', async () => {
    button('Remove Base').click();
    await vi.waitFor(() =>
      expect(question()).toBe('Remove Base from this catalog? 2 offerings and 3 cells go with it.'),
    );
    button('Keep').click();
    await vi.waitFor(() => expect(question()).toBeUndefined());

    button('Remove Sport').click();
    await vi.waitFor(() =>
      expect(question()).toBe(
        'Remove Sport from this catalog? 1 offering and no cells go with it.',
      ),
    );
    button('Remove').click();
    (
      await vi.waitFor(() =>
        backend.expectOne({ method: 'DELETE', url: '/api/catalogs/41/trims/2' }),
      )
    ).flush({ revision: 5 });

    await vi.waitFor(() => expect(button('Remove Europe').disabled).toBe(false));
    button('Remove Europe').click();
    await vi.waitFor(() =>
      expect(question()).toBe(
        'Remove Europe from this catalog? 1 offering and 2 cells go with it.',
      ),
    );
    button('Remove').click();
    (
      await vi.waitFor(() =>
        backend.expectOne({ method: 'DELETE', url: '/api/catalogs/41/regions/EU' }),
      )
    ).flush({ revision: 6 });
  });

  it('offers only active library entries the catalog does not have, and adds the one chosen', async () => {
    dialog().querySelector<HTMLElement>('p-select[inputid="add-trim"]')!.click();
    const options = await vi.waitFor(() => {
      const found = Array.from(document.querySelectorAll<HTMLElement>('[role="option"]'));
      expect(found.map((option) => option.textContent?.trim())).toEqual(['Touring']);
      return found;
    });

    options[0].click();

    const request = await vi.waitFor(() =>
      backend.expectOne({ method: 'POST', url: '/api/catalogs/41/trims' }),
    );
    expect(request.request.body).toEqual({ trimIds: [3] });
    request.flush({ revision: 5 });

    dialog().querySelector<HTMLElement>('p-select[inputid="add-region"]')!.click();
    const regions = await vi.waitFor(() => {
      const found = Array.from(document.querySelectorAll<HTMLElement>('[role="option"]'));
      expect(found.map((option) => option.textContent?.trim())).toEqual(['Asia']);
      return found;
    });
    regions[0].click();
    const second = await vi.waitFor(() =>
      backend.expectOne({ method: 'POST', url: '/api/catalogs/41/regions' }),
    );
    expect(second.request.body).toEqual({ regionCodes: ['ASIA'] });
    second.flush({ revision: 6 });
  });

  it('gives the reason when the backend refuses a change', async () => {
    fixture.componentInstance.refusal = Object.assign(new Error(), {});
    box('Sport is sold in Europe').click();
    (
      await vi.waitFor(() =>
        backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/trims/2/regions' }),
      )
    ).flush({ revision: 5 });

    await vi.waitFor(() => expect(dialog().textContent).toContain('The request was refused.'));
  });

  it('closes when the catalog can no longer be edited', async () => {
    fixture.componentInstance.editable.set(false);

    await vi.waitFor(() => expect(document.querySelector('.p-dialog')).toBeNull());
  });
});
