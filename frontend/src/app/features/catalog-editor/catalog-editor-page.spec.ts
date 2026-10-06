import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input, output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Named } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Cell, MatrixContents } from '../../shared/availability-matrix/matrix';
import { CatalogEditorPage } from './catalog-editor-page';

/**
 * Stands in for the matrix, which has tests of its own. It shows what it was given and what it was
 * told about saves, and a click on it is a person setting the manual transmission of trim 1 in
 * North America to Available.
 */
@Component({
  selector: 'app-availability-matrix',
  host: { '(click)': 'cellChange.emit(manualAvailable)' },
  template: `{{ contents().featureRows.length }} feature rows, editable: {{ editable() }}, hidden:
    {{ hidden() }}`,
})
class MatrixStandIn {
  readonly contents = input.required<MatrixContents>();
  readonly categories = input.required<Named[]>();
  readonly editable = input(false);
  readonly hiddenRegions = input<ReadonlySet<string>>(new Set());
  protected readonly hidden = () => Array.from(this.hiddenRegions()).join(',') || 'none';
  readonly cellChange = output<Cell>();
  protected readonly manualAvailable = manualAvailable;

  saved(cell: Cell): void {
    told.push(['saved', cell]);
  }

  notSaved(cell: Cell, reason: string): void {
    told.push(['not saved', cell, reason]);
  }
}

const manualAvailable: Cell = { featureId: 7, trimId: 1, regionCode: 'NA', availability: 'A' };

/** What the matrix has been told about saves, in order. */
let told: unknown[][];

describe('CatalogEditorPage', () => {
  let backend: HttpTestingController;

  beforeAll(() => {
    // The tabs watch their own width with an observer the test page lacks.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        unobserve(): void {}
        disconnect(): void {}
      },
    );
  });
  afterAll(() => {
    vi.unstubAllGlobals();
  });
  beforeEach(() => {
    told = [];
  });

  const workingCopy = {
    name: 'Winter update',
    versionNumber: null,
    vehicleLineId: 2,
    vehicleLine: 'Compact SUV',
    modelYear: 2027,
    approvedBy: null,
    approvedAt: null,
    owned: true,
    base: { catalogId: 12, modelYear: 2027, versionNumber: 3 },
    snapshot: {
      catalogId: 41,
      lineageId: 3,
      status: 'DRAFT',
      revision: 4,
      trims: [{ id: 1, name: 'Base', sortOrder: 1 }],
      regions: [
        { code: 'NA', name: 'North America' },
        { code: 'EU', name: 'Europe' },
      ],
      offerings: [{ trimId: 1, regionCode: 'NA' }],
      featureRows: [{ id: 1, code: 'ROOF_PANORAMIC', name: 'Panoramic Roof', categoryCode: 'EXT' }],
      cells: [],
    },
  };

  /** Renders the editor for catalog 41, which the backend answers with the given catalog or status. */
  async function page(answer: object | number): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: '41' }) } },
        },
      ],
    });
    TestBed.overrideComponent(CatalogEditorPage, {
      remove: { imports: [AvailabilityMatrix] },
      add: { imports: [MatrixStandIn] },
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(CatalogEditorPage);
    fixture.detectChanges();
    backend.expectOne('/api/reference').flush({ vehicleTypes: [], categories: [], modelYears: [] });
    const request = backend.expectOne('/api/catalogs/41');
    if (typeof answer === 'number') {
      request.flush({ code: 'NOT_FOUND' }, { status: answer, statusText: 'Refused' });
    } else {
      request.flush(answer);
    }
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => expect(element.querySelector('h1')).not.toBeNull());

    return element;
  }

  const described = (element: HTMLElement) =>
    Object.fromEntries(
      Array.from(element.querySelectorAll('dl > div')).map((entry) => [
        entry.querySelector('dt')?.textContent?.trim(),
        entry.querySelector('dd')?.textContent?.trim(),
      ]),
    );

  /** What the backend says of a cell that is none of the catalog's. */
  const NOT_THE_CATALOGS =
    'A cell can be set only for a feature row and an offering of this catalog.';

  const matrixOf = (element: HTMLElement) =>
    element.querySelector<HTMLElement>('app-availability-matrix')!;

  const saveRequest = () =>
    vi.waitFor(() => backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' }));

  const refuse = (status: number, code: string, detail: string) =>
    [
      { code, detail },
      { status, statusText: 'Refused' },
    ] as const;

  const button = (element: HTMLElement, label: string) =>
    Array.from(element.querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    );

  it('describes the catalog and shows its matrix on the Features tab', async () => {
    const element = await page(workingCopy);

    expect(element.querySelector('h1')?.textContent).toBe('Winter update');
    expect(described(element)).toEqual({
      'Vehicle line': 'Compact SUV',
      'Model year': '2027',
      Status: 'Draft',
      Base: 'Approved v3',
    });
    expect(element.querySelector('[role="tab"]')?.textContent?.trim()).toBe('Features');
    expect(element.querySelector('app-availability-matrix')?.textContent).toBe(
      '1 feature rows, editable: true, hidden: none',
    );
  });

  it("shows the matrix read-only unless the catalog is the person's and in status Draft", async () => {
    const matrix = async (catalog: object) =>
      (await page(catalog)).querySelector('app-availability-matrix')?.textContent;

    expect(await matrix({ ...workingCopy, owned: false })).toContain('editable: false');
    TestBed.resetTestingModule();
    expect(
      await matrix({ ...workingCopy, snapshot: { ...workingCopy.snapshot, status: 'APPROVED' } }),
    ).toContain('editable: false');
  });

  it('saves a cell as soon as it is set, as an edit of the revision it has read', async () => {
    const element = await page(workingCopy);

    matrixOf(element).click();

    const save = await vi.waitFor(() =>
      backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' }),
    );
    expect(save.request.headers.get('If-Match')).toBe('"4"');
    expect(save.request.body).toEqual([manualAvailable]);
    save.flush({ revision: 5 });
  });

  it('tells the matrix when a cell has been saved', async () => {
    const element = await page(workingCopy);

    matrixOf(element).click();
    const shown = vi.spyOn(TestBed.inject(MessageService), 'add');
    (await saveRequest()).flush({ revision: 5 });

    await vi.waitFor(() => expect(told).toEqual([['saved', manualAvailable]]));
    expect(shown).not.toHaveBeenCalled();
  });

  it('says there are no new changes when a save changed nothing', async () => {
    const element = await page(workingCopy);
    const shown = vi.spyOn(TestBed.inject(MessageService), 'add');

    matrixOf(element).click();
    // The backend answers with the revision the save was made from: the cell was already so.
    (await saveRequest()).flush({ revision: 4 });

    await vi.waitFor(() =>
      expect(shown).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'info', summary: 'No new changes' }),
      ),
    );
    expect(told).toEqual([['saved', manualAvailable]]);
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(matrixOf(element).textContent).toContain('editable: true');
  });

  it('gives the reason a save was turned down, puts the cell back, and goes on editing', async () => {
    const element = await page(workingCopy);
    const shown = vi.spyOn(TestBed.inject(MessageService), 'add');

    matrixOf(element).click();
    (await saveRequest()).flush(...refuse(422, 'VALIDATION', NOT_THE_CATALOGS));

    await vi.waitFor(() =>
      expect(told).toEqual([['not saved', manualAvailable, `Not saved: ${NOT_THE_CATALOGS}`]]),
    );
    expect(shown).toHaveBeenCalledWith(
      expect.objectContaining({ summary: 'Not saved', detail: NOT_THE_CATALOGS }),
    );
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(matrixOf(element).textContent).toContain('editable: true');

    matrixOf(element).click();
    const next = await saveRequest();
    expect(next.request.headers.get('If-Match')).toBe('"4"');
    next.flush({ revision: 5 });
  });

  it('stops after a revision conflict until the catalog is reloaded', async () => {
    const element = await page(workingCopy);
    const matrix = matrixOf(element);

    matrix.click();
    matrix.click();
    (await saveRequest()).flush(...refuse(412, 'REVISION_CONFLICT', 'Changed somewhere else.'));

    // Both changes go back: the one refused, and the one behind it, which is never sent.
    await vi.waitFor(() =>
      expect(told).toEqual([
        ['not saved', manualAvailable, 'Not saved: Changed somewhere else.'],
        ['not saved', manualAvailable, 'Not sent, because an earlier change was not saved.'],
      ]),
    );
    backend.expectNone({ method: 'PUT', url: '/api/catalogs/41/cells' });
    await vi.waitFor(() =>
      expect(element.querySelector('[role="alert"]')?.textContent).toContain(
        'This catalog was changed somewhere else after you opened it',
      ),
    );
    expect(matrix.textContent).toContain('editable: false');

    // Reloading reads the catalog again, and editing goes on from the revision read.
    button(element, 'Reload')?.click();
    backend.expectOne('/api/catalogs/41').flush({
      ...workingCopy,
      snapshot: { ...workingCopy.snapshot, revision: 9 },
    });
    await vi.waitFor(() => expect(element.querySelector('[role="alert"]')).toBeNull());
    expect(matrixOf(element).textContent).toContain('editable: true');
    matrixOf(element).click();
    const next = await saveRequest();
    expect(next.request.headers.get('If-Match')).toBe('"9"');
    next.flush({ revision: 10 });
  });

  it('stops without trying again when no answer says whether a change was saved', async () => {
    const element = await page(workingCopy);

    matrixOf(element).click();
    (await saveRequest()).error(new ProgressEvent('error'));

    await vi.waitFor(() =>
      expect(element.querySelector('[role="alert"]')?.textContent).toContain(
        'No answer says whether a change of yours was saved.',
      ),
    );
    expect(told).toEqual([
      ['not saved', manualAvailable, 'No answer says whether this change was saved.'],
    ]);
    expect(matrixOf(element).textContent).toContain('editable: false');
    backend.expectNone({ method: 'PUT', url: '/api/catalogs/41/cells' });
    expect(button(element, 'Reload')).toBeDefined();
  });

  describe('when the catalog is no longer in status Draft', () => {
    const NOT_DRAFT = 'Only a catalog in status Draft can be edited.';

    /** Sets a cell of the catalog, which the backend refuses because the catalog is closed. */
    async function editClosed(): Promise<HTMLElement> {
      const element = await page(workingCopy);
      matrixOf(element).click();
      (await saveRequest()).flush(...refuse(409, 'NOT_DRAFT', NOT_DRAFT));

      return element;
    }

    const reread = () => vi.waitFor(() => backend.expectOne('/api/catalogs/41'));

    it('reloads it at once, read-only, and gives the reason', async () => {
      const shown = vi.spyOn(MessageService.prototype, 'add');
      const element = await editClosed();

      (await reread()).flush({
        ...workingCopy,
        snapshot: { ...workingCopy.snapshot, status: 'SUBMITTED' },
      });

      await vi.waitFor(() => expect(described(element).Status).toBe('Submitted'));
      expect(matrixOf(element).textContent).toContain('editable: false');
      expect(shown).toHaveBeenCalledWith(expect.objectContaining({ detail: NOT_DRAFT }));
      expect(element.querySelector('[role="alert"]')).toBeNull();
    });

    it('asks for a reload when it could not be read again', async () => {
      const element = await editClosed();

      (await reread()).flush(null, { status: 503, statusText: 'Unavailable' });

      await vi.waitFor(() =>
        expect(element.querySelector('[role="alert"]')?.textContent).toContain(
          'This catalog can no longer be edited.',
        ),
      );
      expect(matrixOf(element).textContent).toContain('editable: false');
      button(element, 'Reload')?.click();
      (await reread()).flush({
        ...workingCopy,
        snapshot: { ...workingCopy.snapshot, status: 'SUBMITTED' },
      });
      await vi.waitFor(() => expect(element.querySelector('[role="alert"]')).toBeNull());
    });

    it('says so when it is no longer there', async () => {
      const element = await editClosed();

      (await reread()).flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });

      await vi.waitFor(() =>
        expect(element.textContent).toContain('There is no catalog at this address.'),
      );
      expect(element.querySelector('app-availability-matrix')).toBeNull();
    });
  });

  it('shows the change history when the History tab is chosen, read afresh each time', async () => {
    const element = await page(workingCopy);
    const tab = (name: string) =>
      Array.from(element.querySelectorAll<HTMLElement>('[role="tab"]')).find(
        (candidate) => candidate.textContent?.trim() === name,
      )!;
    const history = () =>
      vi.waitFor(() => backend.expectOne((request) => request.url === '/api/catalogs/41/changes'));
    backend.expectNone((request) => request.url === '/api/catalogs/41/changes');

    tab('History').click();
    (await history()).flush({ items: [], total: 0 });
    await vi.waitFor(() =>
      expect(element.textContent).toContain('No changes have been made to this catalog.'),
    );

    tab('Features').click();
    await vi.waitFor(() => expect(element.querySelector('app-history-tab')).toBeNull());
    tab('History').click();
    (await history()).flush({ items: [], total: 0 });
  });

  it('reads the change history only once the changes on their way have been saved', async () => {
    const element = await page(workingCopy);
    const historyTab = Array.from(element.querySelectorAll<HTMLElement>('[role="tab"]')).find(
      (candidate) => candidate.textContent?.trim() === 'History',
    )!;

    matrixOf(element).click();
    const save = await saveRequest();
    historyTab.click();
    await new Promise((resolve) => setTimeout(resolve));
    backend.expectNone((request) => request.url === '/api/catalogs/41/changes');

    save.flush({ revision: 5 });
    const history = await vi.waitFor(() =>
      backend.expectOne((request) => request.url === '/api/catalogs/41/changes'),
    );
    history.flush({ items: [], total: 0 });
  });

  it('hides a region from the matrix when its box is unticked, and shows it again', async () => {
    const element = await page(workingCopy);
    const europe = Array.from(element.querySelectorAll('fieldset label'))
      .find((label) => label.textContent?.trim() === 'Europe')!
      .querySelector('input')!;
    expect(europe.checked).toBe(true);

    europe.click();
    await vi.waitFor(() => expect(matrixOf(element).textContent).toContain('hidden: EU'));

    europe.click();
    await vi.waitFor(() => expect(matrixOf(element).textContent).toContain('hidden: none'));
    backend.expectNone((request) => request.method !== 'GET');
  });

  describe('managing trims and regions', () => {
    const dialog = () => document.querySelector<HTMLElement>('.p-dialog');

    /** Opens the dialog, which first reads the catalog again and then the library. */
    async function manage(): Promise<HTMLElement> {
      const element = await page(workingCopy);
      button(element, 'Manage trims and regions')!.click();
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush({
        ...workingCopy,
        snapshot: { ...workingCopy.snapshot, revision: 6 },
      });
      (await vi.waitFor(() => backend.expectOne('/api/trims'))).flush([]);
      backend.expectOne('/api/regions').flush([]);
      await vi.waitFor(() => expect(dialog()?.textContent).toContain('Manage trims and regions'));

      return element;
    }

    it('is offered only to someone who may edit the catalog', async () => {
      const element = await page({ ...workingCopy, owned: false });

      expect(button(element, 'Manage trims and regions')).toBeUndefined();
    });

    it('waits for the changes on their way before it opens', async () => {
      const element = await page(workingCopy);
      matrixOf(element).click();
      const save = await saveRequest();

      button(element, 'Manage trims and regions')!.click();
      await new Promise((resolve) => setTimeout(resolve));
      backend.expectNone('/api/catalogs/41');
      expect(dialog()).toBeNull();

      save.flush({ revision: 5 });
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush(workingCopy);
      (await vi.waitFor(() => backend.expectOne('/api/trims'))).flush([]);
      backend.expectOne('/api/regions').flush([]);
    });

    it('sends a change as an edit of the revision read and reads the catalog again', async () => {
      const element = await manage();
      const europe = Array.from(element.querySelectorAll('fieldset label'))
        .find((label) => label.textContent?.trim() === 'Europe')!
        .querySelector('input')!;
      europe.click();

      dialog()!
        .querySelector<HTMLInputElement>('input[aria-label="Base is sold in Europe"]')!
        .click();

      const change = await vi.waitFor(() =>
        backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/trims/1/regions' }),
      );
      expect(change.request.headers.get('If-Match')).toBe('"6"');
      expect(change.request.body).toEqual({ regionCodes: ['NA', 'EU'] });
      change.flush({ revision: 7 });
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush({
        ...workingCopy,
        snapshot: {
          ...workingCopy.snapshot,
          revision: 7,
          offerings: [
            { trimId: 1, regionCode: 'NA' },
            { trimId: 1, regionCode: 'EU' },
          ],
        },
      });
      await vi.waitFor(() =>
        expect(
          dialog()!.querySelector<HTMLInputElement>('input[aria-label="Base is sold in Europe"]')
            ?.checked,
        ).toBe(true),
      );
      // What the person has hidden stays hidden when the catalog is read again.
      expect(matrixOf(element).textContent).toContain('hidden: EU');
    });

    it('takes no cell change while it reads the catalog again before opening', async () => {
      const element = await page(workingCopy);

      button(element, 'Manage trims and regions')!.click();
      const reread = await vi.waitFor(() => backend.expectOne('/api/catalogs/41'));
      await vi.waitFor(() => expect(matrixOf(element).textContent).toContain('editable: false'));
      expect(button(element, 'Manage trims and regions')!.disabled).toBe(true);

      reread.flush(workingCopy);
      (await vi.waitFor(() => backend.expectOne('/api/trims'))).flush([]);
      backend.expectOne('/api/regions').flush([]);
      await vi.waitFor(() => expect(matrixOf(element).textContent).toContain('editable: true'));
    });

    it('stays shut when the catalog cannot be read again', async () => {
      const element = await page(workingCopy);

      button(element, 'Manage trims and regions')!.click();
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush(null, {
        status: 503,
        statusText: 'Unavailable',
      });

      await vi.waitFor(() => expect(matrixOf(element).textContent).toContain('editable: true'));
      backend.expectNone('/api/trims');
      expect(dialog()).toBeNull();
    });

    it('closes and asks for a reload when a saved change cannot be read back', async () => {
      const element = await manage();

      dialog()!
        .querySelector<HTMLInputElement>('input[aria-label="Base is sold in Europe"]')!
        .click();
      (
        await vi.waitFor(() =>
          backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/trims/1/regions' }),
        )
      ).flush({ revision: 7 });
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush(null, {
        status: 503,
        statusText: 'Unavailable',
      });

      await vi.waitFor(() => expect(dialog()).toBeNull());
      expect(element.querySelector('[role="alert"]')?.textContent).toContain(
        'Your change was saved, but the catalog could not be read again.',
      );
      expect(matrixOf(element).textContent).toContain('editable: false');

      button(element, 'Reload')!.click();
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush(workingCopy);
      await vi.waitFor(() => expect(element.querySelector('[role="alert"]')).toBeNull());
      expect(matrixOf(element).textContent).toContain('editable: true');
    });

    it('closes and reloads the catalog when it is no longer in status Draft', async () => {
      const element = await manage();

      dialog()!
        .querySelector<HTMLInputElement>('input[aria-label="Base is sold in Europe"]')!
        .click();
      (
        await vi.waitFor(() =>
          backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/trims/1/regions' }),
        )
      ).flush(...refuse(409, 'NOT_DRAFT', 'Only a catalog in status Draft can be edited.'));
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush({
        ...workingCopy,
        snapshot: { ...workingCopy.snapshot, status: 'SUBMITTED' },
      });

      await vi.waitFor(() => expect(dialog()).toBeNull());
      await vi.waitFor(() => expect(described(element).Status).toBe('Submitted'));
      expect(button(element, 'Manage trims and regions')).toBeUndefined();
    });

    it('closes and asks for a reload after a revision conflict', async () => {
      const element = await manage();

      dialog()!
        .querySelector<HTMLInputElement>('input[aria-label="Base is sold in Europe"]')!
        .click();
      (
        await vi.waitFor(() =>
          backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/trims/1/regions' }),
        )
      ).flush(...refuse(412, 'REVISION_CONFLICT', 'Changed somewhere else.'));

      await vi.waitFor(() => expect(dialog()).toBeNull());
      expect(element.querySelector('[role="alert"]')?.textContent).toContain(
        'This catalog was changed somewhere else after you opened it',
      );
    });
  });

  it('names the earlier model year of a base that was carried over', async () => {
    const element = await page({
      ...workingCopy,
      base: { catalogId: 9, modelYear: 2026, versionNumber: 2 },
    });

    expect(described(element).Base).toBe('2026 Approved v2 (carryover)');
  });

  it('says when the catalog started empty', async () => {
    expect(described(await page({ ...workingCopy, base: null })).Base).toBe('None (started empty)');
  });

  it('says so when the address names no catalog the person may open', async () => {
    const element = await page(404);

    expect(element.textContent).toContain('There is no catalog at this address.');
    expect(element.querySelector('app-availability-matrix')).toBeNull();
  });
});
