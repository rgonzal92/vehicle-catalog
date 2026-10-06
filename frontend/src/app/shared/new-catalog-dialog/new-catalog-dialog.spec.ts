import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NewCatalogDialog } from './new-catalog-dialog';

describe('NewCatalogDialog', () => {
  let backend: HttpTestingController;
  let fixture: ComponentFixture<NewCatalogDialog>;

  const lines = [
    { id: 2, code: 'COMPACT_SUV', name: 'Compact SUV', vehicleTypeCode: 'SUV', active: true },
    { id: 3, code: 'FULL_SIZE_SUV', name: 'Full-Size SUV', vehicleTypeCode: 'SUV', active: false },
    { id: 1, code: 'SEDAN', name: 'Sedan', vehicleTypeCode: 'CAR', active: true },
  ];
  const reference = {
    vehicleTypes: [
      { code: 'CAR', name: 'Car' },
      { code: 'SUV', name: 'SUV' },
    ],
    categories: [],
    modelYears: [2026, 2027],
  };

  const dialog = () => document.querySelector<HTMLElement>('.p-dialog');

  const button = (label: string) =>
    Array.from(dialog()?.querySelectorAll('button') ?? []).find(
      (candidate) => candidate.textContent?.trim() === label,
    );

  const startPointRequest = () =>
    vi.waitFor(() => backend.expectOne((request) => request.url === '/api/catalogs/start-point'));

  /** Opens the dialog, for a lineage when one is given, and answers what it asks for first. */
  async function open(lineage?: { vehicleLineId: number; modelYear: number }): Promise<void> {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NewCatalogDialog);
    fixture.detectChanges();

    void fixture.componentInstance.open(lineage);
    backend.expectOne('/api/vehicle-lines').flush(lines);
    backend.expectOne('/api/reference').flush(reference);
    await vi.waitFor(() => expect(dialog()).not.toBeNull());
  }

  /** Opens the dialog for Compact SUV 2026, which has an Approved version 3, and names the catalog. */
  async function openForCompactSuv(): Promise<void> {
    await open({ vehicleLineId: 2, modelYear: 2026 });
    (await startPointRequest()).flush({ kind: 'COPY', modelYear: 2026, versionNumber: 3 });
    const name = dialog()!.querySelector<HTMLInputElement>('#new-catalog-name')!;
    name.value = 'Winter update';
    name.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  afterEach(() => {
    vi.unstubAllGlobals();
    backend.verify();
  });

  it('says where a working copy for the lineage would start from', async () => {
    // What it is opened with may say more than a lineage does, such as the name of a catalog.
    await open({ vehicleLineId: 2, modelYear: 2026, name: 'Autumn update' } as never);
    expect(button('Create')?.disabled).toBe(true);

    const request = await startPointRequest();
    expect(request.request.params.get('vehicleLineId')).toBe('2');
    expect(request.request.params.get('modelYear')).toBe('2026');
    request.flush({ kind: 'CARRYOVER', modelYear: 2025, versionNumber: 2 });

    await vi.waitFor(() =>
      expect(dialog()?.querySelector('[data-start-point]')?.textContent).toBe(
        'Starts from 2025 Approved v2 (carryover)',
      ),
    );
    expect(dialog()?.querySelector<HTMLInputElement>('#new-catalog-name')?.value).toBe('');
    expect(button('Create')?.disabled).toBe(true);
  });

  it('asks for nothing and cannot create until a vehicle line and a model year are chosen', async () => {
    await open();
    await fixture.whenStable();

    expect(dialog()?.querySelector('[data-start-point]')?.textContent).toBe('');
    expect(button('Create')?.disabled).toBe(true);
  });

  it('offers the active vehicle lines of the chosen vehicle type', async () => {
    // A dropdown asks how wide the screen is before it opens, which the test page cannot say.
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    await open({ vehicleLineId: 2, modelYear: 2026 });
    (await startPointRequest()).flush({ kind: 'EMPTY' });

    dialog()!.querySelector<HTMLElement>('p-select[inputid="new-catalog-line"]')!.click();

    await vi.waitFor(() =>
      expect(
        Array.from(document.querySelectorAll('[role="option"]')).map((option) =>
          option.textContent?.trim(),
        ),
      ).toEqual(['Compact SUV']),
    );
  });

  it('creates the working copy and opens it in the catalog editor', async () => {
    await openForCompactSuv();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    button('Create')?.click();
    const request = backend.expectOne({ method: 'POST', url: '/api/catalogs' });
    expect(request.request.body).toEqual({
      name: 'Winter update',
      vehicleLineId: 2,
      modelYear: 2026,
    });
    request.flush({ id: 41 });

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(['/catalogs', 41]));
    await vi.waitFor(() => expect(dialog()).toBeNull());
  });

  it('stays open and gives the reason when the backend refuses', async () => {
    await openForCompactSuv();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');

    button('Create')?.click();
    backend
      .expectOne({ method: 'POST', url: '/api/catalogs' })
      .flush(
        { code: 'NAME_TAKEN', detail: 'Another of your working copies already has this name.' },
        { status: 409, statusText: 'Conflict' },
      );

    await vi.waitFor(() =>
      expect(dialog()?.textContent).toContain(
        'Another of your working copies already has this name.',
      ),
    );
    expect(navigate).not.toHaveBeenCalled();
  });
});
