import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Catalog, CatalogEdit } from '../../core/catalogs';
import { AddFeaturesDialog } from './add-features-dialog';

/** A catalog whose only feature row is the panoramic roof. */
const catalog = {
  snapshot: { catalogId: 41, featureRows: [{ id: 1, code: 'ROOF_PANORAMIC' }] },
} as unknown as Catalog;

const roof = {
  id: 1,
  code: 'ROOF_PANORAMIC',
  name: 'Panoramic Roof',
  categoryCode: 'EXTERIOR',
  kind: 'FEATURE',
};
const removableRoof = { ...roof, id: 2, code: 'ROOF_REMOVABLE', name: 'Removable Roof' };
const tow = {
  id: 3,
  code: 'PACKAGE_TOW',
  name: 'Tow Package',
  categoryCode: 'PACKAGES',
  kind: 'PACKAGE',
};

@Component({
  imports: [AddFeaturesDialog],
  template: `<app-add-features-dialog [catalog]="catalog" [run]="run" [editable]="editable()" />`,
})
class Host {
  readonly dialog = viewChild.required(AddFeaturesDialog);
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

describe('AddFeaturesDialog', () => {
  let backend: HttpTestingController;
  let fixture: ComponentFixture<Host>;

  const dialog = () => document.querySelector<HTMLElement>('.p-dialog')!;

  const searchRequest = (): Promise<TestRequest> =>
    vi.waitFor(() => backend.expectOne((request) => request.url === '/api/features'));

  const box = (label: string) =>
    dialog().querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;

  const button = (label: string) =>
    Array.from(dialog().querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    )!;

  /** Opens the dialog and answers its first search with these features, of this many in all. */
  async function open(items: object[], total = items.length): Promise<void> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();

    fixture.componentInstance.dialog().open();
    backend.expectOne('/api/reference').flush({
      vehicleTypes: [],
      categories: [
        { code: 'EXTERIOR', name: 'Exterior' },
        { code: 'PACKAGES', name: 'Packages' },
      ],
      modelYears: [],
    });
    const first = await searchRequest();
    expect(first.request.params.get('status')).toBe('ACTIVE');
    expect(first.request.params.get('page')).toBe('0');
    expect(first.request.params.get('size')).toBe('10');
    expect(first.request.params.has('query')).toBe(false);
    first.flush({ items, total });
    await vi.waitFor(() => expect(dialog().querySelectorAll('tbody tr').length).toBeGreaterThan(0));
  }

  afterEach(() => backend.verify());

  it('lists the active features, with one that is a row already ticked and out of reach', async () => {
    await open([roof, removableRoof, tow]);

    const rows = Array.from(dialog().querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td'))
        .slice(1)
        .map((cell) => cell.textContent?.replace(/\s+/g, ' ').trim()),
    );
    expect(rows).toEqual([
      ['ROOF_PANORAMIC', 'Panoramic Roof (already a row)', 'Exterior', 'Feature'],
      ['ROOF_REMOVABLE', 'Removable Roof', 'Exterior', 'Feature'],
      ['PACKAGE_TOW', 'Tow Package', 'Packages', 'Package'],
    ]);
    expect(box('Add Panoramic Roof').disabled).toBe(true);
    expect(box('Add Panoramic Roof').checked).toBe(true);
    expect(box('Add Removable Roof').disabled).toBe(false);
    expect(box('Add Removable Roof').checked).toBe(false);
    expect(button('Add').disabled).toBe(true);
  });

  it('searches by code or name from the first page', async () => {
    await open([roof, removableRoof, tow]);
    const query = dialog().querySelector<HTMLInputElement>('#pick-query')!;

    query.value = 'tow';
    query.dispatchEvent(new Event('input'));
    button('Search').click();

    const request = await searchRequest();
    expect(request.request.params.get('query')).toBe('tow');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('status')).toBe('ACTIVE');
    request.flush({ items: [tow], total: 1 });
    await vi.waitFor(() => expect(dialog().querySelectorAll('tbody tr')).toHaveLength(1));
  });

  it('keeps what is ticked from page to page, and adds it all as one edit', async () => {
    await open([roof, removableRoof], 11);

    box('Add Removable Roof').click();
    await vi.waitFor(() =>
      expect(dialog().querySelector('[data-chosen]')?.textContent).toBe('1 feature chosen'),
    );
    const next = await vi.waitFor(() => {
      const found = dialog().querySelector<HTMLButtonElement>('button.p-paginator-next');
      expect(found?.disabled).toBe(false);
      return found!;
    });
    next.click();
    const second = await searchRequest();
    expect(second.request.params.get('page')).toBe('1');
    second.flush({ items: [tow], total: 11 });
    await vi.waitFor(() => expect(box('Add Tow Package')).not.toBeNull());
    box('Add Tow Package').click();
    await vi.waitFor(() =>
      expect(dialog().querySelector('[data-chosen]')?.textContent).toBe('2 features chosen'),
    );

    button('Add').click();

    const added = await vi.waitFor(() =>
      backend.expectOne({ method: 'POST', url: '/api/catalogs/41/features' }),
    );
    expect(added.request.headers.get('If-Match')).toBe('"4"');
    expect(added.request.body).toEqual({ featureIds: [2, 3] });
    added.flush({ revision: 5 });
    await vi.waitFor(() => expect(document.querySelector('.p-dialog')).toBeNull());
  });

  it('stays open and gives the reason when the backend refuses', async () => {
    await open([removableRoof]);
    fixture.componentInstance.refusal = new Error();

    box('Add Removable Roof').click();
    await vi.waitFor(() => expect(button('Add').disabled).toBe(false));
    button('Add').click();
    (
      await vi.waitFor(() =>
        backend.expectOne({ method: 'POST', url: '/api/catalogs/41/features' }),
      )
    ).flush({ revision: 5 });

    await vi.waitFor(() => expect(dialog().textContent).toContain('The request was refused.'));
    expect(box('Add Removable Roof').checked).toBe(true);
  });

  it('starts over each time it is opened: nothing ticked, no filter, the first page read again', async () => {
    await open([removableRoof], 11);
    box('Add Removable Roof').click();
    await vi.waitFor(() => expect(button('Add').disabled).toBe(false));
    button('Cancel').click();
    await vi.waitFor(() => expect(document.querySelector('.p-dialog')).toBeNull());

    fixture.componentInstance.dialog().open();

    const again = await searchRequest();
    expect(again.request.params.get('page')).toBe('0');
    again.flush({ items: [removableRoof], total: 11 });
    await vi.waitFor(() => expect(box('Add Removable Roof')).not.toBeNull());
    expect(box('Add Removable Roof').checked).toBe(false);
    expect(button('Add').disabled).toBe(true);
  });

  it('closes when the catalog can no longer be edited', async () => {
    await open([removableRoof]);

    fixture.componentInstance.editable.set(false);

    await vi.waitFor(() => expect(document.querySelector('.p-dialog')).toBeNull());
  });
});
