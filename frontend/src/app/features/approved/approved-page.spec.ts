import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Named } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Issue, MatrixContents } from '../../shared/availability-matrix/matrix';
import { ApprovedPage } from './approved-page';

/** Stands in for the matrix, which has tests of its own, and shows what it was given. */
@Component({
  selector: 'app-availability-matrix',
  template: `{{ contents().featureRows.length }} feature rows, editable: {{ editable() }},
    {{ issues().length }} issues marked`,
})
class MatrixStandIn {
  readonly contents = input.required<MatrixContents>();
  readonly categories = input.required<Named[]>();
  readonly editable = input(false);
  readonly issues = input<Issue[]>([]);

  show(cell: object): void {
    shown.push(cell);
  }
}

/** The cells the page asked the matrix to show. */
const shown: object[] = [];

describe('ApprovedPage', () => {
  let backend: HttpTestingController;

  const versions = [
    {
      catalogId: 12,
      versionNumber: 2,
      name: 'Autumn update',
      approvedBy: 'Demo Manager',
      approvedAt: '2025-11-03T15:30:00Z',
    },
    {
      catalogId: 11,
      versionNumber: 1,
      name: 'Launch content',
      approvedBy: 'Demo Manager',
      approvedAt: '2025-06-16T14:00:00Z',
    },
  ];
  const catalog = (version: (typeof versions)[number], featureRows: object[]) => ({
    name: version.name,
    versionNumber: version.versionNumber,
    vehicleLineId: 2,
    vehicleLine: 'Compact SUV',
    modelYear: 2026,
    approvedBy: version.approvedBy,
    approvedAt: version.approvedAt,
    snapshot: {
      catalogId: version.catalogId,
      lineageId: 3,
      status: 'APPROVED',
      revision: 0,
      trims: [],
      regions: [],
      offerings: [],
      featureRows,
      cells: [],
    },
  });

  /** Renders the page for lineage 3 and answers its first questions about that lineage. */
  async function page(lineageVersions: object[] | null): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ lineageId: '3' }) } },
        },
      ],
    });
    TestBed.overrideComponent(ApprovedPage, {
      remove: { imports: [AvailabilityMatrix] },
      add: { imports: [MatrixStandIn] },
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ApprovedPage);
    fixture.detectChanges();
    backend.expectOne('/api/reference').flush({ vehicleTypes: [], categories: [], modelYears: [] });
    const asked = backend.expectOne('/api/lineages/3/versions');
    if (lineageVersions) {
      asked.flush(lineageVersions);
    } else {
      asked.flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    }

    return fixture.nativeElement as HTMLElement;
  }

  const catalogRequest = (id: number) => vi.waitFor(() => backend.expectOne(`/api/catalogs/${id}`));

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');

  it('shows the current Approved version read-only, and lists every version', async () => {
    const element = await page(versions);
    (await catalogRequest(12)).flush(catalog(versions[0], [{}, {}, {}]));

    await vi.waitFor(() =>
      expect(element.querySelector('h2')?.textContent?.trim()).toBe('Compact SUV 2026'),
    );
    const shown = element.querySelector('[data-shown]')?.textContent ?? '';
    expect(shown).toContain('Approved version 2');
    expect(shown).toContain('Autumn update');
    expect(shown).toContain('Demo Manager');
    expect(shown).toContain('2025');
    expect(element.querySelector('app-availability-matrix')?.textContent).toContain(
      '3 feature rows, editable: false',
    );
    expect(rows(element)[0]).toContain('Autumn update');
    expect(rows(element)[0]).toContain('Shown below');
    expect(rows(element)[1]).toContain('Launch content');
    expect(rows(element)[1]).toContain('Demo Manager');
  });

  it('lists the issues the version has today, marks their cells, and shows a cell', async () => {
    const roofOnBase: Issue = {
      code: 'REQUIRED_NOT_OFFERED',
      severity: 'ERROR',
      trimId: 1,
      regionCode: 'NA',
      featureId: 7,
      relatedFeatureIds: [8],
      rule: { origin: 'GLOBAL', key: '12' },
      message:
        'Panoramic Roof requires Tow Package, which is not offered on Base in North America.',
    };
    const neverOffered: Issue = {
      code: 'FEATURE_NEVER_OFFERED',
      severity: 'WARNING',
      trimId: null,
      regionCode: null,
      featureId: 7,
      relatedFeatureIds: [],
      rule: null,
      message: 'Panoramic Roof is not offered in any offering.',
    };
    const element = await page(versions);
    const version = catalog(versions[0], [{ id: 7, name: 'Panoramic Roof' }]);
    shown.length = 0;
    (await catalogRequest(12)).flush({
      ...version,
      snapshot: {
        ...version.snapshot,
        trims: [{ id: 1, name: 'Base', sortOrder: 1 }],
        regions: [{ code: 'NA', name: 'North America' }],
      },
      issues: [roofOnBase, neverOffered],
    });

    await vi.waitFor(() =>
      expect(
        Array.from(element.querySelectorAll('[data-issue-counts] p-tag'), (tag) =>
          tag.textContent?.trim(),
        ),
      ).toEqual(['1 Error', '1 Warning']),
    );
    const listed = Array.from(element.querySelectorAll('app-issue-list tbody tr'), (row) =>
      Array.from(row.querySelectorAll('td'), (cell) => cell.textContent?.trim()),
    );
    expect(listed).toEqual([
      [
        'Error',
        roofOnBase.message,
        'Panoramic Roof, Base in North America',
        'Global rule',
        'Show cell',
      ],
      ['Warning', neverOffered.message, 'Panoramic Roof', '', ''],
    ]);
    expect(element.querySelector('app-availability-matrix')?.textContent).toContain(
      '2 issues marked',
    );

    element
      .querySelector<HTMLElement>('button[aria-label^="Show the cell of this issue"]')!
      .click();

    expect(shown).toEqual([{ featureId: 7, trimId: 1, regionCode: 'NA' }]);
  });

  it('compares another version with the one shown, from the earlier to the later', async () => {
    const element = await page(versions);
    (await catalogRequest(12)).flush({ ...catalog(versions[0], []), issues: [] });
    const compare = await vi.waitFor(() => {
      const found = element.querySelector<HTMLElement>(
        'button[aria-label="Compare version 1 with version 2"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(element.querySelector('#comparison')).toBeNull();

    compare.click();

    const asked = await vi.waitFor(() =>
      backend.expectOne((request) => request.url === '/api/catalogs/12/diff'),
    );
    expect(asked.request.params.get('against')).toBe('11');
    asked.flush({
      trimsAdded: [],
      trimsRemoved: [],
      regionsAdded: [],
      regionsRemoved: [],
      offeringsAdded: [],
      offeringsRemoved: [],
      featureRowsAdded: [
        { id: 7, code: 'POWERTRAIN_HYBRID', kind: 'FEATURE', name: 'Hybrid Powertrain' },
      ],
      featureRowsRemoved: [],
      cellsChanged: [],
      rulesAdded: [],
      rulesRemoved: [],
      rulesChanged: [],
    });

    await vi.waitFor(() =>
      expect(element.querySelector('#comparison')?.textContent?.trim()).toBe(
        'Changes from version 1 to version 2',
      ),
    );
    expect(element.querySelector('app-catalog-changes')?.textContent).toContain(
      'Hybrid Powertrain (POWERTRAIN_HYBRID)',
    );
    await vi.waitFor(() =>
      expect(document.activeElement).toBe(element.querySelector('#comparison')),
    );

    element.querySelector<HTMLElement>('button[aria-label="Close the comparison"]')!.click();
    await vi.waitFor(() => expect(element.querySelector('#comparison')).toBeNull());
  });

  it('says so when the version has no issues', async () => {
    const element = await page(versions);
    (await catalogRequest(12)).flush({ ...catalog(versions[0], []), issues: [] });

    await vi.waitFor(() =>
      expect(element.querySelector('app-issue-list')?.textContent).toContain(
        'This version has no issues, as the library is today.',
      ),
    );
    expect(element.querySelector('[data-issue-counts]')?.textContent?.trim()).toBe('No issues');
  });

  it('opens the new catalog dialog for the lineage', async () => {
    const element = await page(versions);
    (await catalogRequest(12)).flush(catalog(versions[0], []));
    const create = await vi.waitFor(() => {
      const button = Array.from(element.querySelectorAll('button')).find(
        (candidate) => candidate.textContent?.trim() === 'Create working copy',
      );
      expect(button).toBeDefined();
      return button!;
    });

    create.click();
    backend
      .expectOne('/api/vehicle-lines')
      .flush([
        { id: 2, code: 'COMPACT_SUV', name: 'Compact SUV', vehicleTypeCode: 'SUV', active: true },
      ]);

    const asked = await vi.waitFor(() =>
      backend.expectOne((request) => request.url === '/api/catalogs/start-point'),
    );
    expect(asked.request.params.get('vehicleLineId')).toBe('2');
    expect(asked.request.params.get('modelYear')).toBe('2026');
    asked.flush({ kind: 'COPY', modelYear: 2026, versionNumber: 2 });
    await vi.waitFor(() =>
      expect(document.querySelector('.p-dialog')?.textContent).toContain('Starts from Approved v2'),
    );
    // The new catalog does not take the name of the Approved version it was opened from.
    expect(document.querySelector<HTMLInputElement>('#new-catalog-name')?.value).toBe('');
  });

  it('shows another version when it is chosen from the list', async () => {
    const element = await page(versions);
    (await catalogRequest(12)).flush(catalog(versions[0], [{}, {}, {}]));
    const show = await vi.waitFor(() => {
      const button = element.querySelector<HTMLButtonElement>(
        'button[aria-label="Show version 1"]',
      );
      expect(button).not.toBeNull();
      return button!;
    });

    show.click();
    (await catalogRequest(11)).flush(catalog(versions[1], [{}]));

    await vi.waitFor(() =>
      expect(element.querySelector('[data-shown]')?.textContent).toContain('Approved version 1'),
    );
    expect(element.querySelector('app-availability-matrix')?.textContent).toContain(
      '1 feature rows',
    );
    expect(rows(element)[1]).toContain('Shown below');
    expect(element.querySelector('button[aria-label="Show version 2"]')).not.toBeNull();
  });

  it('says so when the lineage has no Approved version', async () => {
    const element = await page([]);

    await vi.waitFor(() =>
      expect(element.textContent).toContain('There is no Approved version at this address.'),
    );
    expect(element.querySelector('app-availability-matrix')).toBeNull();
  });

  it('says so when there is no such lineage', async () => {
    const element = await page(null);

    await vi.waitFor(() =>
      expect(element.textContent).toContain('There is no Approved version at this address.'),
    );
  });

  it('shows the version chosen last, whichever answer arrives last', async () => {
    const third = { ...versions[0], catalogId: 13, versionNumber: 3, name: 'Winter update' };
    const element = await page([third, ...versions]);
    (await catalogRequest(13)).flush(catalog(third, [{}]));
    const show = (version: number) =>
      vi.waitFor(() => {
        const button = element.querySelector<HTMLButtonElement>(
          `button[aria-label="Show version ${version}"]`,
        );
        expect(button).not.toBeNull();
        return button!;
      });

    (await show(2)).click();
    const slow = await catalogRequest(12);
    (await show(1)).click();
    const fast = await catalogRequest(11);
    fast.flush(catalog(versions[1], [{}]));
    slow.flush(catalog(versions[0], [{}, {}, {}]));
    await TestBed.inject(ApplicationRef).whenStable();

    expect(element.querySelector('[data-shown]')?.textContent).toContain('Approved version 1');
  });

  it('keeps showing a version when another one cannot be read', async () => {
    const element = await page(versions);
    (await catalogRequest(12)).flush(catalog(versions[0], [{}, {}, {}]));
    const show = await vi.waitFor(() => {
      const button = element.querySelector<HTMLButtonElement>(
        'button[aria-label="Show version 1"]',
      );
      expect(button).not.toBeNull();
      return button!;
    });

    show.click();
    (await catalogRequest(11)).flush(null, { status: 503, statusText: 'Service Unavailable' });
    await TestBed.inject(ApplicationRef).whenStable();

    expect(element.querySelector('[data-shown]')?.textContent).toContain('Approved version 2');
    expect(element.textContent).not.toContain('There is no Approved version at this address.');
  });

  it('stands in for the page until it has been read', async () => {
    const element = await page(versions);
    expect(element.querySelector('app-loading')).not.toBeNull();

    (await catalogRequest(12)).flush(catalog(versions[0], [{}, {}, {}]));

    await vi.waitFor(() => expect(element.querySelector('app-loading')).toBeNull());
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });

  it('says so when the page cannot be read, and reads it again when asked', async () => {
    const element = await page(versions);
    (await catalogRequest(12)).flush(null, { status: 500, statusText: 'Server Error' });

    const failed = await vi.waitFor(() => {
      const found = element.querySelector('[role="alert"]');
      expect(found?.textContent).toContain('The Approved catalog could not be read.');
      return found!;
    });
    Array.from(failed.querySelectorAll('button'))
      .find((candidate) => candidate.textContent?.trim() === 'Try again')!
      .click();

    (await vi.waitFor(() => backend.expectOne('/api/lineages/3/versions'))).flush(versions);
    (await catalogRequest(12)).flush(catalog(versions[0], [{}, {}, {}]));
    await vi.waitFor(() =>
      expect(element.querySelector('h2')?.textContent?.trim()).toBe('Compact SUV 2026'),
    );
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });
});
