import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Named } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { MatrixContents } from '../../shared/availability-matrix/matrix';
import { CatalogEditorPage } from './catalog-editor-page';

/** Stands in for the matrix, which has tests of its own, and shows what it was given. */
@Component({
  selector: 'app-availability-matrix',
  template: `{{ contents().featureRows.length }} feature rows, editable: {{ editable() }}`,
})
class MatrixStandIn {
  readonly contents = input.required<MatrixContents>();
  readonly categories = input.required<Named[]>();
  readonly editable = input(false);
}

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
    base: { catalogId: 12, modelYear: 2027, versionNumber: 3 },
    snapshot: {
      catalogId: 41,
      lineageId: 3,
      status: 'DRAFT',
      revision: 0,
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

  it('describes the catalog and shows its matrix read-only on the Features tab', async () => {
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
      '1 feature rows, editable: false',
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
