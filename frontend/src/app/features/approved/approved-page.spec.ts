import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Named } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { MatrixContents } from '../../shared/availability-matrix/matrix';
import { ApprovedPage } from './approved-page';

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
      expect(element.querySelector('h1')?.textContent).toBe('Compact SUV 2026'),
    );
    const shown = element.querySelector('[data-shown]')?.textContent ?? '';
    expect(shown).toContain('Approved version 2');
    expect(shown).toContain('Autumn update');
    expect(shown).toContain('Demo Manager');
    expect(shown).toContain('2025');
    expect(element.querySelector('app-availability-matrix')?.textContent).toBe(
      '3 feature rows, editable: false',
    );
    expect(rows(element)[0]).toContain('Autumn update');
    expect(rows(element)[0]).toContain('Shown below');
    expect(rows(element)[1]).toContain('Launch content');
    expect(rows(element)[1]).toContain('Demo Manager');
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
});
