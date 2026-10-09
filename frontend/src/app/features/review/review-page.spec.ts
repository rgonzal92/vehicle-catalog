import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Named } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Issue, MatrixChanges, MatrixContents } from '../../shared/availability-matrix/matrix';
import { ReviewPage } from './review-page';

/** Stands in for the matrix, which has tests of its own, and shows what it was given. */
@Component({
  selector: 'app-availability-matrix',
  template: `{{ contents().featureRows.length }} feature rows, editable: {{ editable() }}, changed:
    {{ changed() }}, added rows: {{ addedRows() }}, added offerings: {{ addedOfferings() }}`,
})
class MatrixStandIn {
  readonly contents = input.required<MatrixContents>();
  readonly categories = input.required<Named[]>();
  readonly editable = input(false);
  readonly issues = input<Issue[]>([]);
  readonly changes = input<MatrixChanges | null>(null);

  protected changed(): string {
    return [...(this.changes()?.before ?? [])].map(([cell, was]) => `${cell} was ${was}`).join();
  }

  protected addedRows(): string {
    return [...(this.changes()?.addedFeatures ?? [])].join();
  }

  protected addedOfferings(): string {
    return [...(this.changes()?.addedOfferings ?? [])].join();
  }
}

describe('ReviewPage', () => {
  let backend: HttpTestingController;

  const submitted = {
    name: 'Winter update',
    versionNumber: null,
    vehicleLineId: 2,
    vehicleLine: 'Compact SUV',
    modelYear: 2027,
    owned: false,
    owner: 'Ana Author',
    vehicleLineActive: true,
    submitNote: 'Ready for review.',
    submittedAt: '2026-10-09T10:00:00Z',
    base: { catalogId: 12, modelYear: 2026, versionNumber: 2 },
    snapshot: {
      catalogId: 41,
      lineageId: 3,
      status: 'SUBMITTED',
      revision: 5,
      trims: [{ id: 1, name: 'Base', sortOrder: 1 }],
      regions: [{ code: 'NA', name: 'North America' }],
      offerings: [{ trimId: 1, regionCode: 'NA' }],
      featureRows: [
        { id: 7, code: 'ROOF', kind: 'FEATURE', name: 'Panoramic Roof', categoryCode: 'EXTERIOR' },
      ],
      cells: [],
      rules: [],
    },
    issues: [],
  };
  const changes = {
    trimsAdded: [],
    trimsRemoved: [{ id: 2, name: 'Sport', sortOrder: 2 }],
    regionsAdded: [],
    regionsRemoved: [],
    offeringsAdded: [{ trimId: 1, trim: 'Base', regionCode: 'NA', region: 'North America' }],
    offeringsRemoved: [],
    featureRowsAdded: [{ id: 7, code: 'ROOF', kind: 'FEATURE', name: 'Panoramic Roof' }],
    featureRowsRemoved: [],
    cellsChanged: [
      {
        featureId: 8,
        featureCode: 'PACKAGE_TOW',
        feature: 'Tow Package',
        trimId: 1,
        trim: 'Base',
        regionCode: 'NA',
        region: 'North America',
        before: 'A',
        after: 'S',
      },
    ],
    rulesAdded: [{ key: 'a', rule: 'Tow Package requires Heavy-Duty Cooling' }],
    rulesRemoved: [],
    rulesChanged: [],
  };

  /** Renders the review of catalog 41, which the backend answers with these or with this status. */
  async function page(catalog: object | number, changed: object = changes): Promise<HTMLElement> {
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
    TestBed.overrideComponent(ReviewPage, {
      remove: { imports: [AvailabilityMatrix] },
      add: { imports: [MatrixStandIn] },
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ReviewPage);
    fixture.detectChanges();
    backend.expectOne('/api/reference').flush({ vehicleTypes: [], categories: [], modelYears: [] });
    const asked = backend.expectOne('/api/catalogs/41');
    const diff = backend.expectOne('/api/catalogs/41/diff?against=base');
    if (typeof catalog === 'number') {
      asked.flush({ code: 'NOT_FOUND' }, { status: catalog, statusText: 'Refused' });
      diff.flush({ code: 'NOT_FOUND' }, { status: catalog, statusText: 'Refused' });
    } else {
      asked.flush(catalog);
      diff.flush(changed);
    }
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => expect(element.querySelector('h2, p, app-read-failed')).not.toBeNull());

    return element;
  }

  afterEach(() => backend.verify());

  const described = (element: HTMLElement) =>
    Object.fromEntries(
      Array.from(element.querySelectorAll('dl > div')).map((entry) => [
        entry.querySelector('dt')?.textContent?.trim(),
        entry.querySelector('dd')?.textContent?.trim(),
      ]),
    );

  it('says whose catalog it is, what its owner said, and what it is based on', async () => {
    const element = await page(submitted);

    expect(element.querySelector('h2')?.textContent).toBe('Winter update');
    const header = described(element);
    expect(header['Owner']).toBe('Ana Author');
    expect(header['Note for the reviewer']).toBe('Ready for review.');
    expect(header['Base']).toBe('2026 Approved v2 (carryover)');
    expect(header['Vehicle line']).toBe('Compact SUV');
  });

  it('lists what the catalog changes against its base, and has the matrix mark it', async () => {
    const element = await page(submitted);

    const listed = element.querySelector('app-catalog-changes')?.textContent ?? '';
    expect(listed).toContain('Sport');
    expect(listed).toContain('Tow Package (PACKAGE_TOW)');
    expect(listed).toContain('Tow Package requires Heavy-Duty Cooling');
    expect(
      element.querySelector('app-availability-matrix')?.textContent?.replace(/\s+/g, ' '),
    ).toBe(
      '1 feature rows, editable: false, changed: 8:1:NA was A, added rows: 7, added offerings: 1:NA',
    );
  });

  it('lists the issues the catalog has', async () => {
    const element = await page({
      ...submitted,
      issues: [
        {
          code: 'NO_REGIONS',
          severity: 'ERROR',
          trimId: null,
          regionCode: null,
          featureId: null,
          relatedFeatureIds: [],
          rule: null,
          message: 'The catalog has no regions.',
        },
      ],
    });

    expect(element.querySelector('[data-issue-counts]')?.textContent?.trim()).toBe('1 Error');
    expect(element.querySelector('app-issue-list')?.textContent).toContain(
      'The catalog has no regions.',
    );
  });

  it('says so when the catalog changes nothing', async () => {
    const empty = Object.fromEntries(Object.keys(changes).map((kind) => [kind, []]));

    const element = await page(submitted, empty);

    expect(element.querySelector('app-catalog-changes')?.textContent).toContain(
      'This catalog changes nothing against its base.',
    );
  });

  it('says that a catalog that is not Submitted is not waiting for review, and links to it', async () => {
    const element = await page({
      ...submitted,
      snapshot: { ...submitted.snapshot, status: 'DRAFT' },
    });

    const notice = element.querySelector('[data-notice="nothing-to-review"]');
    expect(notice?.textContent).toContain('This catalog is not waiting for review.');
    expect(notice?.querySelector('a')?.getAttribute('href')).toBe('/catalogs/41');
    expect(element.querySelector('app-availability-matrix')).toBeNull();
  });

  it('says so when there is no catalog to review at the address', async () => {
    const element = await page(404);

    expect(element.textContent).toContain('There is no catalog to review at this address.');
  });
});
