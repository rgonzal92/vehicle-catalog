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
 * Stands in for the matrix, which has tests of its own. It shows what it was given, and a click on
 * it is a person setting the manual transmission of trim 1 in North America to Available.
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
}

const manualAvailable: Cell = { featureId: 7, trimId: 1, regionCode: 'NA', availability: 'A' };

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

  it('saves the next cell only once the one before it is saved, from the revision that led to', async () => {
    const element = await page(workingCopy);
    const matrix = element.querySelector<HTMLElement>('app-availability-matrix')!;

    matrix.click();
    matrix.click();

    const first = await vi.waitFor(() =>
      backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' }),
    );
    first.flush({ revision: 5 });
    const second = await vi.waitFor(() =>
      backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' }),
    );
    expect(second.request.headers.get('If-Match')).toBe('"5"');
    second.flush({ revision: 6 });
  });

  it('says why a save was refused', async () => {
    const element = await page(workingCopy);
    const shown = vi.spyOn(TestBed.inject(MessageService), 'add');

    element.querySelector<HTMLElement>('app-availability-matrix')!.click();
    (
      await vi.waitFor(() => backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' }))
    ).flush(
      { code: 'VALIDATION', detail: 'A cell can be set only for a feature row of this catalog.' },
      { status: 422, statusText: 'Unprocessable' },
    );

    await vi.waitFor(() =>
      expect(shown).toHaveBeenCalledWith(
        expect.objectContaining({
          detail: 'A cell can be set only for a feature row of this catalog.',
        }),
      ),
    );
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
