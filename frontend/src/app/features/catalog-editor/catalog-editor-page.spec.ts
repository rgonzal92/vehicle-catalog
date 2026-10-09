import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input, output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Named } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Cell, FeatureRow, Issue, MatrixContents } from '../../shared/availability-matrix/matrix';
import { CatalogEditorPage } from './catalog-editor-page';

/**
 * Stands in for the matrix, which has tests of its own. It shows what it was given and what it was
 * told about saves. A click on it is a person setting the manual transmission of trim 1 in North
 * America to Available, and a double click is a person asking for its first feature row, which has
 * two cells, to be removed.
 */
@Component({
  selector: 'app-availability-matrix',
  host: {
    '(click)': 'cellChange.emit(manualAvailable)',
    '(dblclick)': 'featureRemove.emit({ feature: contents().featureRows[0], cells: 2 })',
  },
  template: `{{ shown() }} of {{ contents().featureRows.length }} feature rows, editable:
    {{ editable() }}, hidden: {{ hidden() }}`,
})
class MatrixStandIn {
  readonly contents = input.required<MatrixContents>();
  readonly categories = input.required<Named[]>();
  readonly editable = input(false);
  readonly hiddenRegions = input<ReadonlySet<string>>(new Set());
  readonly featureFilter = input<(feature: FeatureRow) => boolean>(() => true);
  readonly issues = input<Issue[]>([]);
  readonly cellChange = output<Cell>();
  readonly featureRemove = output<{ feature: FeatureRow; cells: number }>();
  protected readonly manualAvailable = manualAvailable;
  protected readonly hidden = () => Array.from(this.hiddenRegions()).join(',') || 'none';
  protected readonly shown = () =>
    this.contents()
      .featureRows.filter(this.featureFilter())
      .map(({ code }) => code)
      .join(',') || 'none';

  saved(cell: Cell): void {
    told.push(['saved', cell]);
  }

  focusOn(feature?: FeatureRow): void {
    focused.push(feature?.code ?? 'where the keyboard starts');
  }

  notSaved(cell: Cell, reason: string): void {
    told.push(['not saved', cell, reason]);
  }

  show(cell: Pick<Cell, 'featureId' | 'trimId' | 'regionCode'>): void {
    shown.push(cell);
  }
}

const manualAvailable: Cell = { featureId: 7, trimId: 1, regionCode: 'NA', availability: 'A' };

/** What the matrix has been told about saves, in order. */
let told: unknown[][];

/** Where the matrix has been asked to put the focus, in order. */
let focused: string[];

/** The cells the matrix has been asked to bring into view, in order. */
let shown: unknown[];

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
    focused = [];
    shown = [];
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
      featureRows: [
        {
          id: 1,
          code: 'ROOF_PANORAMIC',
          kind: 'FEATURE',
          name: 'Panoramic Roof',
          categoryCode: 'EXTERIOR',
        },
        {
          id: 2,
          code: 'PACKAGE_TOW',
          kind: 'PACKAGE',
          name: 'Tow Package',
          categoryCode: 'PACKAGES',
        },
      ],
      cells: [],
    },
  };

  /** Renders the editor for catalog 41, which the backend answers with the given catalog or status. */
  async function page(answer: object | number | 'not yet'): Promise<HTMLElement> {
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
    const element = fixture.nativeElement as HTMLElement;
    if (answer === 'not yet') {
      return element;
    }
    const request = backend.expectOne('/api/catalogs/41');
    if (typeof answer === 'number') {
      request.flush({ code: 'NOT_FOUND' }, { status: answer, statusText: 'Refused' });
    } else {
      request.flush(answer);
    }
    await vi.waitFor(() => expect(element.querySelector('h2, p, app-read-failed')).not.toBeNull());

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

    expect(element.querySelector('h2')?.textContent).toBe('Winter update');
    expect(described(element)).toEqual({
      'Vehicle line': 'Compact SUV',
      'Model year': '2027',
      Status: 'Draft',
      Base: 'Approved v3',
    });
    expect(element.querySelector('[role="tab"]')?.textContent?.trim()).toBe('Features');
    expect(element.querySelector('app-availability-matrix')?.textContent).toBe(
      'ROOF_PANORAMIC,PACKAGE_TOW of 2 feature rows, editable: true, hidden: none',
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

  describe('renaming the catalog in the header', () => {
    const renameRequest = () =>
      vi.waitFor(() => backend.expectOne({ method: 'PATCH', url: '/api/catalogs/41' }));

    /** Starts renaming, types the name, and saves it. */
    async function renameTo(element: HTMLElement, name: string): Promise<void> {
      button(element, 'Rename')!.click();
      const box = await vi.waitFor(() => {
        const found = element.querySelector<HTMLInputElement>('#catalog-name');
        expect(found).not.toBeNull();
        return found!;
      });
      expect(box.value).toBe('Winter update');
      box.value = name;
      box.dispatchEvent(new Event('input'));
      await vi.waitFor(() => expect(button(element, 'Save')!.disabled).toBe(false));
      button(element, 'Save')!.click();
    }

    it('is offered only to someone who may edit the catalog', async () => {
      const element = await page({ ...workingCopy, owned: false });

      expect(button(element, 'Rename')).toBeUndefined();
    });

    it('saves the new name as an edit of the revision read, and shows it', async () => {
      const element = await page(workingCopy);

      await renameTo(element, '  Spring update ');

      const request = await renameRequest();
      expect(request.request.headers.get('If-Match')).toBe('"4"');
      expect(request.request.body).toEqual({ name: 'Spring update' });
      request.flush({ revision: 5 });
      await vi.waitFor(() =>
        expect(element.querySelector('h2')?.textContent).toBe('Spring update'),
      );
      // The matrix is left as it is, and the next edit is one of the new revision.
      expect(matrixOf(element).textContent).toContain('editable: true');
      matrixOf(element).click();
      const next = await saveRequest();
      expect(next.request.headers.get('If-Match')).toBe('"5"');
      next.flush({ revision: 6 });
    });

    it('keeps the box open with the reason when the name is taken, and editing goes on', async () => {
      const element = await page(workingCopy);

      await renameTo(element, 'Coupe');
      (await renameRequest()).flush(
        ...refuse(409, 'NAME_TAKEN', 'Another of your working copies already has this name.'),
      );

      await vi.waitFor(() =>
        expect(element.textContent).toContain(
          'Another of your working copies already has this name.',
        ),
      );
      expect(element.querySelector('#catalog-name')).not.toBeNull();
      expect(element.querySelector('[role="alert"] button')).toBeNull();
      expect(matrixOf(element).textContent).toContain('editable: true');

      button(element, 'Cancel')!.click();
      await vi.waitFor(() =>
        expect(element.querySelector('h2')?.textContent).toBe('Winter update'),
      );
      // The focus is back on the button that opened the box.
      await vi.waitFor(() => expect(document.activeElement).toBe(button(element, 'Rename')));
    });

    it('saves once, however often Enter is pressed, and says so when the name is the one it has', async () => {
      const element = await page(workingCopy);
      const shown = vi.spyOn(TestBed.inject(MessageService), 'add');

      await renameTo(element, 'Winter update');
      button(element, 'Save')!.click();

      // The backend answers with the revision the rename was made from: nothing changed.
      (await renameRequest()).flush({ revision: 4 });
      await vi.waitFor(() =>
        expect(shown).toHaveBeenCalledWith(expect.objectContaining({ summary: 'No new changes' })),
      );
      backend.expectNone({ method: 'PATCH', url: '/api/catalogs/41' });
      await vi.waitFor(() =>
        expect(element.querySelector('h2')?.textContent).toBe('Winter update'),
      );
    });

    it('leaves the name as it is when Escape is pressed', async () => {
      const element = await page(workingCopy);
      button(element, 'Rename')!.click();
      const box = await vi.waitFor(() => {
        const found = element.querySelector<HTMLInputElement>('#catalog-name');
        expect(found).not.toBeNull();
        return found!;
      });
      // The box takes the focus, with the name ready to be typed over.
      await vi.waitFor(() => expect(document.activeElement).toBe(box));

      box.value = 'Typed and dropped';
      box.dispatchEvent(new Event('input'));
      box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

      await vi.waitFor(() =>
        expect(element.querySelector('h2')?.textContent).toBe('Winter update'),
      );
      backend.expectNone({ method: 'PATCH', url: '/api/catalogs/41' });
    });

    it('cannot save an empty name', async () => {
      const element = await page(workingCopy);

      button(element, 'Rename')!.click();
      const box = await vi.waitFor(() => {
        const found = element.querySelector<HTMLInputElement>('#catalog-name');
        expect(found).not.toBeNull();
        return found!;
      });
      for (const noName of ['', '   ']) {
        box.value = 'A name';
        box.dispatchEvent(new Event('input'));
        await vi.waitFor(() => expect(button(element, 'Save')!.disabled).toBe(false));
        box.value = noName;
        box.dispatchEvent(new Event('input'));
        await vi.waitFor(() => expect(button(element, 'Save')!.disabled).toBe(true));
      }
    });
  });

  describe('narrowing the matrix to some feature rows', () => {
    const shownIn = (element: HTMLElement) => matrixOf(element).textContent?.split(' of ')[0];

    const type = (element: HTMLElement, text: string) => {
      const query = element.querySelector<HTMLInputElement>('#row-query')!;
      query.value = text;
      query.dispatchEvent(new Event('input'));
    };

    it('searches code and name, whatever the case, and says how many rows are shown', async () => {
      const element = await page(workingCopy);
      expect(element.querySelector('[data-rows-shown]')?.textContent?.trim()).toBe(
        '2 of 2 feature rows shown',
      );

      type(element, 'tow pack');
      await vi.waitFor(() => expect(shownIn(element)).toBe('PACKAGE_TOW'));
      expect(element.querySelector('[data-rows-shown]')?.textContent?.trim()).toBe(
        '1 of 2 feature rows shown',
      );

      type(element, 'ROOF_');
      await vi.waitFor(() => expect(shownIn(element)).toBe('ROOF_PANORAMIC'));

      type(element, 'nothing like it');
      await vi.waitFor(() => expect(shownIn(element)).toBe('none'));

      type(element, '');
      await vi.waitFor(() => expect(shownIn(element)).toBe('ROOF_PANORAMIC,PACKAGE_TOW'));
      backend.expectNone((request) => request.method !== 'GET');
    });
  });

  describe('removing a feature row', () => {
    const dialog = () => document.querySelector<HTMLElement>('.p-dialog');

    const dialogButton = (label: string) =>
      Array.from(dialog()?.querySelectorAll('button') ?? []).find(
        (candidate) => candidate.textContent?.trim() === label,
      )!;

    /** Asks for the panoramic roof's row, which has two cells, to be removed. */
    async function ask(): Promise<HTMLElement> {
      const element = await page(workingCopy);
      matrixOf(element).dispatchEvent(new MouseEvent('dblclick'));
      await vi.waitFor(() =>
        expect(
          dialog()?.querySelector('[data-question]')?.textContent?.replace(/\s+/g, ' '),
        ).toContain(
          'Remove Panoramic Roof (ROOF_PANORAMIC) from this catalog? 2 cells go with it.',
        ),
      );

      return element;
    }

    it('asks first, and keeps the row when told to', async () => {
      await ask();

      dialogButton('Keep').click();

      await vi.waitFor(() => expect(dialog()).toBeNull());
      backend.expectNone((request) => request.method !== 'GET');
      // The focus goes back to the button that asked.
      await vi.waitFor(() => expect(focused).toEqual(['ROOF_PANORAMIC']));
    });

    it('lists the rules that name the row, and removes them with it', async () => {
      const towNeedsRoof = {
        key: 'tow-needs-roof',
        kind: 'REQUIRES',
        sourceFeatureId: 2,
        targetFeatureIds: [1],
        allTrims: true,
        trimIds: [],
        allRegions: true,
        regionCodes: [],
        pairKey: null,
      };
      const element = await page({
        ...workingCopy,
        snapshot: { ...workingCopy.snapshot, rules: [towNeedsRoof] },
      });
      matrixOf(element).dispatchEvent(new MouseEvent('dblclick'));

      const going = () => dialog()?.querySelector('[data-rules-going]');
      await vi.waitFor(() =>
        expect(going()?.querySelector('p')?.textContent?.trim()).toBe(
          '1 rule of this catalog names it. Removing the row deletes the rule too:',
        ),
      );
      expect(Array.from(going()!.querySelectorAll('li'), (item) => item.textContent)).toEqual([
        'Tow Package requires Panoramic Roof',
      ]);
      expect(dialogButton('Remove')).toBe(undefined);
      dialogButton('Remove with its rules').click();

      const removal = await vi.waitFor(() =>
        backend.expectOne({
          method: 'DELETE',
          url: '/api/catalogs/41/features/1?removeRules=true',
        }),
      );
      removal.flush({
        revision: 5,
        issues: [],
        rulesDeleted: ['Tow Package requires Panoramic Roof'],
      });
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush({
        ...workingCopy,
        snapshot: {
          ...workingCopy.snapshot,
          revision: 5,
          featureRows: [workingCopy.snapshot.featureRows[1]],
        },
      });
      await vi.waitFor(() => expect(dialog()).toBeNull());
    });

    it('removes the row as an edit of the revision read, and reads the catalog again', async () => {
      const element = await ask();

      dialogButton('Remove').click();

      const removal = await vi.waitFor(() =>
        backend.expectOne({ method: 'DELETE', url: '/api/catalogs/41/features/1' }),
      );
      expect(removal.request.headers.get('If-Match')).toBe('"4"');
      removal.flush({ revision: 5 });
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush({
        ...workingCopy,
        snapshot: {
          ...workingCopy.snapshot,
          revision: 5,
          featureRows: [workingCopy.snapshot.featureRows[1]],
        },
      });
      await vi.waitFor(() => expect(dialog()).toBeNull());
      expect(matrixOf(element).textContent).toContain('PACKAGE_TOW of 1 feature rows');
      expect(element.querySelector('[data-rows-shown]')?.textContent?.trim()).toBe(
        '1 of 1 feature row shown',
      );
      // The matrix is asked for the row that was removed, and starts the keyboard over without it.
      await vi.waitFor(() => expect(focused).toEqual(['ROOF_PANORAMIC']));
    });

    it('removes the row once, however often Remove is pressed', async () => {
      await ask();

      dialogButton('Remove').click();
      dialogButton('Remove').click();

      const removal = await vi.waitFor(() =>
        backend.expectOne({ method: 'DELETE', url: '/api/catalogs/41/features/1' }),
      );
      await vi.waitFor(() => expect(dialogButton('Keep').disabled).toBe(true));
      removal.flush({ revision: 5 });
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush(workingCopy);
      await vi.waitFor(() => expect(dialog()).toBeNull());
      backend.expectNone({ method: 'DELETE', url: '/api/catalogs/41/features/1' });
    });

    it('keeps the question open with the reason when the backend turns the removal down', async () => {
      await ask();

      dialogButton('Remove').click();
      (
        await vi.waitFor(() =>
          backend.expectOne({ method: 'DELETE', url: '/api/catalogs/41/features/1' }),
        )
      ).flush(...refuse(422, 'VALIDATION', 'This row cannot be removed.'));

      await vi.waitFor(() =>
        expect(dialog()?.textContent).toContain('This row cannot be removed.'),
      );
      expect(dialog()?.querySelector('[data-question]')).not.toBeNull();
    });

    it('closes the question when the row turns out to be gone and the catalog is read again', async () => {
      const element = await ask();

      dialogButton('Remove').click();
      (
        await vi.waitFor(() =>
          backend.expectOne({ method: 'DELETE', url: '/api/catalogs/41/features/1' }),
        )
      ).flush(...refuse(404, 'NOT_FOUND', 'There is nothing at this address.'));
      (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush({
        ...workingCopy,
        snapshot: { ...workingCopy.snapshot, featureRows: [workingCopy.snapshot.featureRows[1]] },
      });

      await vi.waitFor(() => expect(dialog()).toBeNull());
      expect(matrixOf(element).textContent).toContain('PACKAGE_TOW of 1 feature rows');
    });
  });

  it('opens the feature picker from the Features tab', async () => {
    const element = await page(workingCopy);

    button(element, 'Add features')!.click();

    (await vi.waitFor(() => backend.expectOne((request) => request.url === '/api/features'))).flush(
      { items: [], total: 0 },
    );
    await vi.waitFor(() =>
      expect(document.querySelector('.p-dialog')?.textContent).toContain('Add features'),
    );
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

  it('stands in for the catalog until it has been read', async () => {
    const element = await page('not yet');
    expect(element.querySelector('app-loading')).not.toBeNull();

    backend.expectOne('/api/catalogs/41').flush(workingCopy);

    await vi.waitFor(() => expect(element.querySelector('h2')?.textContent).toBe('Winter update'));
    expect(element.querySelector('app-loading')).toBeNull();
  });

  it('says so when the catalog cannot be read, and reads it again when asked', async () => {
    const element = await page(500);

    const failed = element.querySelector('[role="alert"]')!;
    expect(failed.textContent).toContain('The catalog could not be read.');
    expect(element.querySelector('app-loading')).toBeNull();
    Array.from(failed.querySelectorAll('button'))
      .find((candidate) => candidate.textContent?.trim() === 'Try again')!
      .click();

    (await vi.waitFor(() => backend.expectOne('/api/catalogs/41'))).flush(workingCopy);
    await vi.waitFor(() => expect(element.querySelector('h2')?.textContent).toBe('Winter update'));
    expect(element.querySelector('app-read-failed')).toBeNull();
  });

  describe('the issues of the catalog', () => {
    const emptyOffering: Issue = {
      code: 'OFFERING_EMPTY',
      severity: 'ERROR',
      trimId: 1,
      regionCode: 'NA',
      featureId: null,
      relatedFeatureIds: [],
      rule: null,
      message: 'Base in North America has no Standard or Available feature.',
    };
    const neverOffered: Issue = {
      code: 'FEATURE_NEVER_OFFERED',
      severity: 'WARNING',
      trimId: null,
      regionCode: null,
      featureId: 2,
      relatedFeatureIds: [],
      rule: null,
      message: 'Tow Package is not offered in any offering.',
    };
    const roofOnBase: Issue = {
      code: 'REQUIRED_NOT_OFFERED',
      severity: 'ERROR',
      trimId: 1,
      regionCode: 'NA',
      featureId: 1,
      relatedFeatureIds: [2],
      rule: { origin: 'GLOBAL', key: '12' },
      message:
        'Panoramic Roof requires Tow Package, which is not offered on Base in North America.',
    };

    const counts = (element: HTMLElement) =>
      Array.from(element.querySelectorAll('[data-issue-counts] p-tag'), (tag) =>
        tag.textContent?.trim(),
      ).join(', ');

    const tab = (element: HTMLElement, name: string) =>
      Array.from(element.querySelectorAll<HTMLElement>('[role="tab"]')).find(
        (candidate) => candidate.textContent?.trim() === name,
      )!;

    /** What each row of the Issues tab says, cell by cell. */
    const listed = (element: HTMLElement) =>
      Array.from(element.querySelectorAll('p-tabpanel:nth-of-type(3) tbody tr')).map((row) =>
        Array.from(row.querySelectorAll('td'), (cell) => cell.textContent?.trim()),
      );

    it('says how many Errors and Warnings there are, and lists them with what each is about', async () => {
      const element = await page({ ...workingCopy, issues: [emptyOffering, neverOffered] });

      expect(counts(element)).toBe('1 Error, 1 Warning');
      tab(element, 'Issues').click();

      await vi.waitFor(() =>
        expect(listed(element)).toEqual([
          ['Error', emptyOffering.message, 'Base in North America', '', ''],
          ['Warning', neverOffered.message, 'Tow Package', '', ''],
        ]),
      );
    });

    it('shows the rules on the Rules tab, read when the tab is chosen', async () => {
      const element = await page({ ...workingCopy, issues: [] });
      expect(element.querySelector('app-rules-tab')).toBeNull();

      tab(element, 'Rules').click();

      (await vi.waitFor(() => backend.expectOne('/api/global-rules'))).flush([]);
      await vi.waitFor(() =>
        expect(element.querySelector('app-rules-tab')?.textContent).toContain(
          'This catalog has no rules',
        ),
      );
    });

    it('shows the rule of an issue that comes from a rule of the catalog', async () => {
      const rule = {
        key: 'roof-needs-tow',
        kind: 'REQUIRES',
        sourceFeatureId: 1,
        targetFeatureIds: [2],
        allTrims: true,
        trimIds: [],
        allRegions: true,
        regionCodes: [],
        pairKey: null,
      };
      const ofTheCatalog = { ...roofOnBase, rule: { origin: 'CATALOG', key: rule.key } };
      const element = await page({
        ...workingCopy,
        snapshot: { ...workingCopy.snapshot, rules: [rule] },
        issues: [roofOnBase, ofTheCatalog],
      });
      tab(element, 'Issues').click();
      const show = await vi.waitFor(() => {
        const found = element.querySelectorAll<HTMLElement>(
          'button[aria-label^="Show the rule of this issue"]',
        );
        expect(found, 'only the issue of the rule of the catalog').toHaveLength(1);
        return found[0];
      });
      expect(listed(element)[1][3]).toBe('Catalog rule');

      show.click();

      (await vi.waitFor(() => backend.expectOne('/api/global-rules'))).flush([]);
      await vi.waitFor(() =>
        expect(
          element.querySelector('tr[data-rule="roof-needs-tow"]')?.hasAttribute('data-shown-rule'),
        ).toBe(true),
      );
      expect(tab(element, 'Rules').getAttribute('aria-selected')).toBe('true');
    });

    it('says so when the catalog has no issues', async () => {
      const element = await page({ ...workingCopy, issues: [] });

      expect(counts(element)).toBe('No issues');
      tab(element, 'Issues').click();

      await vi.waitFor(() => expect(element.textContent).toContain('This catalog has no issues.'));
    });

    it('gives the matrix the issues, and takes the ones an edit answers with', async () => {
      const element = await page({ ...workingCopy, issues: [roofOnBase] });
      expect(counts(element)).toBe('1 Error');

      matrixOf(element).click();
      (await saveRequest()).flush({ revision: 5, issues: [neverOffered] });

      await vi.waitFor(() => expect(counts(element)).toBe('1 Warning'));
    });

    it('shows the cell of an issue on the Features tab, whatever the filters keep from view', async () => {
      const element = await page({ ...workingCopy, issues: [roofOnBase] });
      tab(element, 'Issues').click();
      await vi.waitFor(() =>
        expect(tab(element, 'Issues').getAttribute('aria-selected')).toBe('true'),
      );
      const show = await vi.waitFor(() => {
        const found = element.querySelector<HTMLElement>(
          'button[aria-label^="Show the cell of this issue"]',
        );
        expect(found).not.toBeNull();
        return found!;
      });
      expect(listed(element)[0].slice(2, 4)).toEqual([
        'Panoramic Roof, Base in North America',
        'Global rule',
      ]);

      show.click();

      await vi.waitFor(() =>
        expect(shown).toEqual([{ featureId: 1, trimId: 1, regionCode: 'NA' }]),
      );
      expect(tab(element, 'Features').getAttribute('aria-selected')).toBe('true');
    });
  });
});
