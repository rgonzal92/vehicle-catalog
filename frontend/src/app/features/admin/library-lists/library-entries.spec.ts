import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { LibraryEntries } from './library-entries';
import { REGIONS, TRIMS } from './library-list';

describe('LibraryEntries', () => {
  let entries: LibraryEntries;
  let backend: HttpTestingController;

  const base = { id: 1, name: 'Base', sortOrder: 1, active: true };
  const europe = { code: 'EU', name: 'Europe', sortOrder: 1, active: true };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), LibraryEntries],
    });
    entries = TestBed.inject(LibraryEntries);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('adds an entry, then shows the list as the backend has it', async () => {
    const loading = entries.load(TRIMS);
    backend.expectOne('/api/trims').flush([base]);
    await loading;
    const sport = { id: 2, name: 'Sport', sortOrder: 2, active: true };

    const adding = entries.add({ name: 'Sport' });
    const request = backend.expectOne({ method: 'POST', url: '/api/trims' });
    expect(request.request.body).toEqual({ name: 'Sport' });
    request.flush(sport);
    const reread = await vi.waitFor(() => backend.expectOne({ method: 'GET', url: '/api/trims' }));
    reread.flush([base, sport]);
    await adding;

    expect(entries.entries()).toEqual([base, sport]);
  });

  it('addresses a trim by its id and a region by its code', async () => {
    const loadingTrims = entries.load(TRIMS);
    backend.expectOne('/api/trims').flush([base]);
    await loadingTrims;
    void entries.change(base, { name: 'Basic', sortOrder: 1, active: true }).catch(() => undefined);
    backend
      .expectOne({ method: 'PUT', url: '/api/trims/1' })
      .flush(null, { status: 500, statusText: 'Error' });

    const loadingRegions = entries.load(REGIONS);
    backend.expectOne('/api/regions').flush([europe]);
    await loadingRegions;
    void entries.change(europe, { name: 'EU', sortOrder: 1, active: true }).catch(() => undefined);
    backend
      .expectOne({ method: 'PUT', url: '/api/regions/EU' })
      .flush(null, { status: 500, statusText: 'Error' });
  });

  it('leaves the list alone when the backend refuses a change', async () => {
    const loading = entries.load(TRIMS);
    backend.expectOne('/api/trims').flush([base]);
    await loading;

    const changing = entries.change(base, { name: 'Sport', sortOrder: 1, active: true });
    backend
      .expectOne({ method: 'PUT', url: '/api/trims/1' })
      .flush({ code: 'NAME_TAKEN' }, { status: 409, statusText: 'Conflict' });

    await expect(changing).rejects.toBeTruthy();
    expect(entries.entries()).toEqual([base]);
  });
});
