import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { VehicleLines } from './vehicle-lines';

describe('VehicleLines', () => {
  let vehicleLines: VehicleLines;
  let backend: HttpTestingController;

  const sedan = { id: 1, code: 'SEDAN', name: 'Sedan', vehicleTypeCode: 'CAR', active: true };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    vehicleLines = TestBed.inject(VehicleLines);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  async function loaded(): Promise<void> {
    const loading = vehicleLines.load();
    backend.expectOne('/api/vehicle-lines').flush([sedan]);
    await loading;
  }

  /** The list is read again a moment after a change is accepted. */
  const listRequest = () =>
    vi.waitFor(() => backend.expectOne({ method: 'GET', url: '/api/vehicle-lines' }));

  it('lists the vehicle lines the backend has', async () => {
    await loaded();

    expect(vehicleLines.lines()).toEqual([sedan]);
  });

  it('adds a vehicle line, then shows the list as the backend has it', async () => {
    await loaded();
    const compact = {
      id: 2,
      code: 'COMPACT_SUV',
      name: 'Compact SUV',
      vehicleTypeCode: 'SUV',
      active: true,
    };

    const adding = vehicleLines.add({
      code: 'COMPACT_SUV',
      name: 'Compact SUV',
      vehicleTypeCode: 'SUV',
    });
    const request = backend.expectOne({ method: 'POST', url: '/api/vehicle-lines' });
    expect(request.request.body).toEqual({
      code: 'COMPACT_SUV',
      name: 'Compact SUV',
      vehicleTypeCode: 'SUV',
    });
    request.flush(compact);
    (await listRequest()).flush([compact, sedan]);
    await adding;

    expect(vehicleLines.lines()).toEqual([compact, sedan]);
  });

  it('changes a vehicle line, then shows the list as the backend has it', async () => {
    await loaded();
    const saloon = { ...sedan, name: 'Saloon', active: false };

    const changing = vehicleLines.change(1, {
      name: 'Saloon',
      vehicleTypeCode: 'CAR',
      active: false,
    });
    const request = backend.expectOne({ method: 'PUT', url: '/api/vehicle-lines/1' });
    expect(request.request.body).toEqual({ name: 'Saloon', vehicleTypeCode: 'CAR', active: false });
    request.flush(saloon);
    (await listRequest()).flush([saloon]);
    await changing;

    expect(vehicleLines.lines()).toEqual([saloon]);
  });

  it('leaves the list alone when the backend refuses a change', async () => {
    await loaded();

    const changing = vehicleLines.change(1, {
      name: 'Coupe',
      vehicleTypeCode: 'CAR',
      active: true,
    });
    backend
      .expectOne({ method: 'PUT', url: '/api/vehicle-lines/1' })
      .flush({ code: 'NAME_TAKEN' }, { status: 409, statusText: 'Conflict' });

    await expect(changing).rejects.toBeTruthy();
    expect(vehicleLines.lines()).toEqual([sedan]);
  });
});
