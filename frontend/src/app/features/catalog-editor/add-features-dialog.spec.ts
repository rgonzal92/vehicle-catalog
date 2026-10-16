import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { Component, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { Catalog, Catalogs } from '../../core/catalogs';
import { AddFeaturesDialog } from './add-features-dialog';
import { Editing } from './editing';

/** A working copy, read at revision 4, whose only feature row is the panoramic roof. */
const catalog = {
  owned: true,
  issues: [],
  snapshot: {
    catalogId: 41,
    status: 'DRAFT',
    revision: 4,
    featureRows: [{ id: 1, code: 'ROOF_PANORAMIC' }],
  },
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
  template: `<app-add-features-dialog [catalog]="catalog" />`,
})
class Host {
  readonly dialog = viewChild.required(AddFeaturesDialog);
  readonly catalog = catalog;
}

describe('AddFeaturesDialog', () => {
  let backend: HttpTestingController;
  let fixture: ComponentFixture<Host>;
  let editing: Editing;

  const dialog = () => document.querySelector<HTMLElement>('.p-dialog')!;

  const searchRequest = (): Promise<TestRequest> =>
    vi.waitFor(() => backend.expectOne((request) => request.url === '/api/features'));

  const box = (label: string) =>
    dialog().querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;

  const button = (label: string) =>
    Array.from(dialog().querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    )!;

  const addRequest = () =>
    vi.waitFor(() => backend.expectOne({ method: 'POST', url: '/api/catalogs/41/features' }));

  /**
   * Opens the working copy for editing, then the dialog, and answers its first search with these
   * features, of this many in all.
   */
  async function open(items: object[], total = items.length): Promise<void> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService, Editing],
    });
    backend = TestBed.inject(HttpTestingController);
    editing = TestBed.inject(Editing);
    const opened = editing.open(41);
    backend.expectOne('/api/catalogs/41').flush(catalog);
    await opened;
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
    expect(box('Panoramic Roof is already a feature row').disabled).toBe(true);
    expect(box('Panoramic Roof is already a feature row').checked).toBe(true);
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

  it('filters by category and kind, and pages over the search as it was started', async () => {
    // A dropdown asks how wide the screen is before it opens, which the test page cannot say.
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    await open([roof, removableRoof, tow], 30);
    const choose = async (dropdown: string, option: string) => {
      dialog().querySelector<HTMLElement>(`p-select[inputid="${dropdown}"]`)!.click();
      const found = await vi.waitFor(() => {
        const wanted = Array.from(document.querySelectorAll<HTMLElement>('[role="option"]')).find(
          (candidate) => candidate.textContent?.trim() === option,
        );
        expect(wanted).toBeDefined();
        return wanted!;
      });
      found.click();
    };

    await choose('pick-kind', 'Package');
    const byKind = await searchRequest();
    expect(byKind.request.params.get('kind')).toBe('PACKAGE');
    expect(byKind.request.params.has('category')).toBe(false);
    byKind.flush({ items: [tow], total: 30 });

    await choose('pick-category', 'Packages');
    const byBoth = await searchRequest();
    expect(byBoth.request.params.get('kind')).toBe('PACKAGE');
    expect(byBoth.request.params.get('category')).toBe('PACKAGES');
    byBoth.flush({ items: [tow], total: 30 });

    // Text typed but not searched for takes no part in paging.
    const query = dialog().querySelector<HTMLInputElement>('#pick-query')!;
    query.value = 'not searched for';
    query.dispatchEvent(new Event('input'));
    const next = await vi.waitFor(() => {
      const found = dialog().querySelector<HTMLButtonElement>('button.p-paginator-next');
      expect(found?.disabled).toBe(false);
      return found!;
    });
    next.click();
    const second = await searchRequest();
    expect(second.request.params.get('page')).toBe('1');
    expect(second.request.params.get('category')).toBe('PACKAGES');
    expect(second.request.params.has('query')).toBe(false);
    second.flush({ items: [], total: 30 });
    vi.unstubAllGlobals();
  });

  it('keeps what is ticked from page to page, and adds it all as one edit', async () => {
    await open([roof, removableRoof], 11);

    box('Add Removable Roof').click();
    await vi.waitFor(() =>
      expect(dialog().querySelector('[data-ticked]')?.textContent).toBe('1 feature ticked'),
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
      expect(dialog().querySelector('[data-ticked]')?.textContent).toBe('2 features ticked'),
    );

    button('Add').click();

    const added = await addRequest();
    expect(added.request.headers.get('If-Match')).toBe('"4"');
    expect(added.request.body).toEqual({ featureIds: [2, 3] });
    added.flush({ revision: 5, issues: [] });
    (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush(catalog);
    await vi.waitFor(() => expect(document.querySelector('.p-dialog')).toBeNull());
  });

  it('stays open and gives the reason when the backend refuses', async () => {
    await open([removableRoof]);

    box('Add Removable Roof').click();
    await vi.waitFor(() => expect(button('Add').disabled).toBe(false));
    button('Add').click();
    (await addRequest()).flush(
      { code: 'VALIDATION', detail: 'Removable Roof is retired.' },
      { status: 422, statusText: 'Unprocessable' },
    );

    await vi.waitFor(() => expect(dialog().textContent).toContain('Removable Roof is retired.'));
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

  it('closes when another edit stops the editor, such as a save of a cell still on its way', async () => {
    await open([removableRoof]);
    const catalogs = TestBed.inject(Catalogs);

    void editing.edit((revision) =>
      catalogs.setCells(41, revision, [
        { featureId: 1, trimId: 1, regionCode: 'NA', availability: 'S' },
      ]),
    );
    (
      await vi.waitFor(() => backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' }))
    ).flush(
      { code: 'REVISION_CONFLICT', detail: 'Changed somewhere else.' },
      { status: 412, statusText: 'Precondition Failed' },
    );

    await vi.waitFor(() => expect(document.querySelector('.p-dialog')).toBeNull());
    expect(editing.stopped()).toBe('conflict');
  });

  it('closes after a revision conflict, which stops the editor, and shows no refusal', async () => {
    await open([removableRoof]);
    box('Add Removable Roof').click();
    await vi.waitFor(() => expect(button('Add').disabled).toBe(false));

    button('Add').click();
    (await addRequest()).flush(
      { code: 'REVISION_CONFLICT', detail: 'Changed somewhere else.' },
      { status: 412, statusText: 'Precondition Failed' },
    );

    await vi.waitFor(() => expect(document.querySelector('.p-dialog')).toBeNull());
    expect(editing.stopped()).toBe('conflict');
    expect(document.body.textContent).not.toContain('Changed somewhere else.');
  });
});
