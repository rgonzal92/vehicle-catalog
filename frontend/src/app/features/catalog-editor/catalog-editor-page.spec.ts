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
  template: `{{ contents().featureRows.length }} feature rows, editable: {{ editable() }}`,
})
class MatrixStandIn {
  readonly contents = input.required<MatrixContents>();
  readonly categories = input.required<Named[]>();
  readonly editable = input(false);
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

  beforeEach(() => {
    told = [];
  });
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
      trims: [],
      regions: [],
      offerings: [],
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
      '1 feature rows, editable: true',
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

    element.querySelector<HTMLElement>('app-availability-matrix')!.click();

    const save = await vi.waitFor(() =>
      backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' }),
    );
    expect(save.request.headers.get('If-Match')).toBe('"4"');
    expect(save.request.body).toEqual([manualAvailable]);
    save.flush({ revision: 5 });
  });

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

  it('tells the matrix when a cell has been saved', async () => {
    const element = await page(workingCopy);

    matrixOf(element).click();
    (await saveRequest()).flush({ revision: 5 });

    await vi.waitFor(() => expect(told).toEqual([['saved', manualAvailable]]));
  });

  it('gives the reason a save was refused, puts the cell back, and goes on editing', async () => {
    const element = await page(workingCopy);
    const shown = vi.spyOn(TestBed.inject(MessageService), 'add');

    matrixOf(element).click();
    (await saveRequest()).flush(
      ...refuse(
        422,
        'VALIDATION',
        'A cell can be set only for a feature row and an offering of this catalog.',
      ),
    );

    await vi.waitFor(() =>
      expect(told).toEqual([
        [
          'not saved',
          manualAvailable,
          'A cell can be set only for a feature row and an offering of this catalog.',
        ],
      ]),
    );
    expect(shown).toHaveBeenCalledWith(
      expect.objectContaining({
        summary: 'Not saved',
        detail: 'A cell can be set only for a feature row and an offering of this catalog.',
      }),
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
        ['not saved', manualAvailable, 'Changed somewhere else.'],
        [
          'not saved',
          manualAvailable,
          'An earlier change was not saved, so this one was not sent.',
        ],
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
        'No answer says whether your last change was saved.',
      ),
    );
    expect(told).toEqual([
      [
        'not saved',
        manualAvailable,
        'No answer says whether your last change was saved. Reload the catalog to see, and to go on.',
      ],
    ]);
    expect(matrixOf(element).textContent).toContain('editable: false');
    backend.expectNone({ method: 'PUT', url: '/api/catalogs/41/cells' });
    expect(button(element, 'Reload')).toBeDefined();
  });

  it('reloads the catalog read-only when it is no longer in status Draft', async () => {
    const element = await page(workingCopy);
    const shown = vi.spyOn(TestBed.inject(MessageService), 'add');

    matrixOf(element).click();
    (await saveRequest()).flush(
      ...refuse(409, 'NOT_DRAFT', 'Only a catalog in status Draft can be edited.'),
    );

    const reread = await vi.waitFor(() => backend.expectOne('/api/catalogs/41'));
    reread.flush({ ...workingCopy, snapshot: { ...workingCopy.snapshot, status: 'SUBMITTED' } });
    await vi.waitFor(() => expect(described(element).Status).toBe('Submitted'));
    expect(matrixOf(element).textContent).toContain('editable: false');
    expect(shown).toHaveBeenCalledWith(
      expect.objectContaining({ detail: 'Only a catalog in status Draft can be edited.' }),
    );
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });
});
