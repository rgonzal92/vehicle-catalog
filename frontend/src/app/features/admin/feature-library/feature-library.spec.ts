import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Feature, FeatureLibrary, FeatureSearch } from './feature-library';

describe('FeatureLibrary', () => {
  let library: FeatureLibrary;
  let backend: HttpTestingController;

  const roof: Feature = {
    id: 1,
    code: 'ROOF_PANORAMIC',
    name: 'Panoramic Roof',
    description: 'A glass roof.',
    categoryCode: 'EXTERIOR',
    kind: 'FEATURE',
    status: 'ACTIVE',
    version: 3,
  };
  const everything: FeatureSearch = {
    query: '',
    category: '',
    kind: '',
    status: '',
    page: 0,
    size: 25,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    library = TestBed.inject(FeatureLibrary);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  const searchRequest = () =>
    vi.waitFor(() =>
      backend.expectOne((request) => request.method === 'GET' && request.url === '/api/features'),
    );

  async function found(search: FeatureSearch, items: Feature[], total: number): Promise<void> {
    const finding = library.find(search);
    (await searchRequest()).flush({ items, total });
    await finding;
  }

  it('shows the page of features the backend finds, and how many there are in all', async () => {
    await found(everything, [roof], 41);

    expect(library.features()).toEqual([roof]);
    expect(library.total()).toBe(41);
  });

  it('sends the filters that are set and leaves out the ones that are not', async () => {
    const finding = library.find({ ...everything, query: 'roof', kind: 'FEATURE', page: 2 });
    const request = await searchRequest();
    request.flush({ items: [], total: 0 });
    await finding;

    const sent = request.request.params;
    expect(sent.keys().sort()).toEqual(['kind', 'page', 'query', 'size']);
    expect(sent.get('query')).toBe('roof');
    expect(sent.get('kind')).toBe('FEATURE');
    expect(sent.get('page')).toBe('2');
    expect(sent.get('size')).toBe('25');
  });

  it('drops a slow answer to an earlier search', async () => {
    const first = library.find({ ...everything, query: 'ro' });
    const slow = await searchRequest();
    const second = library.find({ ...everything, query: 'roof' });
    const fast = await vi.waitFor(() =>
      backend.expectOne((request) => request.params.get('query') === 'roof'),
    );

    fast.flush({ items: [roof], total: 1 });
    slow.flush({ items: [], total: 0 });
    await Promise.all([first, second]);

    expect(library.features()).toEqual([roof]);
  });

  it('adds a feature, then repeats the search', async () => {
    await found({ ...everything, query: 'roof' }, [], 0);

    const adding = library.add({
      code: 'ROOF_PANORAMIC',
      name: 'Panoramic Roof',
      description: 'A glass roof.',
      categoryCode: 'EXTERIOR',
      kind: 'FEATURE',
    });
    const request = backend.expectOne({ method: 'POST', url: '/api/features' });
    expect(request.request.body.code).toBe('ROOF_PANORAMIC');
    request.flush(roof);
    const repeated = await searchRequest();
    expect(repeated.request.params.get('query')).toBe('roof');
    repeated.flush({ items: [roof], total: 1 });
    await adding;

    expect(library.features()).toEqual([roof]);
  });

  it('retires and reactivates a feature', async () => {
    await found(everything, [roof], 1);
    const retired: Feature = { ...roof, status: 'RETIRED', version: 4 };

    const retiring = library.retire(1);
    backend.expectOne({ method: 'POST', url: '/api/features/1/retire' }).flush(retired);
    (await searchRequest()).flush({ items: [retired], total: 1 });
    await retiring;
    expect(library.features()).toEqual([retired]);

    const reactivating = library.reactivate(1);
    backend.expectOne({ method: 'POST', url: '/api/features/1/reactivate' }).flush(roof);
    (await searchRequest()).flush({ items: [roof], total: 1 });
    await reactivating;
    expect(library.features()).toEqual([roof]);
  });

  it('reads the page again after a refused edit, so the next one starts from the feature as it now is', async () => {
    await found(everything, [roof], 1);
    const theirs: Feature = { ...roof, name: 'Glass Roof', version: 4 };

    const changing = library.change(1, {
      name: 'Sky Roof',
      description: '',
      categoryCode: 'EXTERIOR',
      version: 3,
    });
    const request = backend.expectOne({ method: 'PUT', url: '/api/features/1' });
    expect(request.request.body.version).toBe(3);
    request.flush({ code: 'CONFLICT' }, { status: 409, statusText: 'Conflict' });
    (await searchRequest()).flush({ items: [theirs], total: 1 });

    await expect(changing).rejects.toBeTruthy();
    expect(library.features()).toEqual([theirs]);
  });
});
