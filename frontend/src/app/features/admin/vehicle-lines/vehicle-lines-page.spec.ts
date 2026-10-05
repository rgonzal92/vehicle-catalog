import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { VehicleLinesPage } from './vehicle-lines-page';

describe('VehicleLinesPage', () => {
  let backend: HttpTestingController;

  const lines = [
    { id: 2, code: 'COMPACT_SUV', name: 'Compact SUV', vehicleTypeCode: 'SUV', active: true },
    { id: 1, code: 'SEDAN', name: 'Sedan', vehicleTypeCode: 'CAR', active: false },
  ];
  const reference = {
    vehicleTypes: [
      { code: 'CAR', name: 'Car' },
      { code: 'SUV', name: 'SUV' },
    ],
    categories: [],
    modelYears: [],
  };

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');

  async function page(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(VehicleLinesPage);
    fixture.detectChanges();
    backend.expectOne('/api/vehicle-lines').flush(lines);
    backend.expectOne('/api/reference').flush(reference);
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => expect(rows(element).length).toBe(lines.length));
    await fixture.whenStable();

    return element;
  }

  it('lists each vehicle line with its type and whether it is active', async () => {
    const [compact, sedan] = rows(await page());

    expect(compact).toContain('COMPACT_SUV');
    expect(compact).toContain('Compact SUV');
    expect(compact).toContain('Active');
    expect(sedan).toContain('Car');
    expect(sedan).toContain('Inactive');
  });

  it('deactivates a vehicle line from its row', async () => {
    const element = await page();

    element
      .querySelector<HTMLButtonElement>('button[aria-label="Deactivate Compact SUV"]')
      ?.click();
    const request = backend.expectOne({ method: 'PUT', url: '/api/vehicle-lines/2' });
    expect(request.request.body).toEqual({
      name: 'Compact SUV',
      vehicleTypeCode: 'SUV',
      active: false,
    });
    request.flush({ ...lines[0], active: false });

    await vi.waitFor(() => expect(rows(element)[0]).toContain('Inactive'));
  });
});
