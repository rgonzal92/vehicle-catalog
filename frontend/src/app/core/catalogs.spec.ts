import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Catalogs, SAVE_PATIENCE, startPointInWords } from './catalogs';

describe('Catalogs', () => {
  const manualAvailable = { featureId: 7, trimId: 1, regionCode: 'NA', availability: 'A' } as const;
  let catalogs: Catalogs;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    catalogs = TestBed.inject(Catalogs);
    backend = TestBed.inject(HttpTestingController);
  });
  afterEach(() => vi.useRealTimers());

  it('saves cells as an edit of a revision and answers with the revision that led to', async () => {
    const saved = catalogs.setCells(41, 4, [manualAvailable]);

    const request = backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' });
    expect(request.request.headers.get('If-Match')).toBe('"4"');
    expect(request.request.body).toEqual([manualAvailable]);
    request.flush({ revision: 5 });

    await expect(saved).resolves.toBe(5);
  });

  const noTrims = {
    code: 'NO_TRIMS',
    severity: 'ERROR',
    trimId: null,
    regionCode: null,
    featureId: null,
    relatedFeatureIds: [],
    rule: null,
    message: 'The catalog has no trims.',
  } as const;

  it('keeps the issues a catalog is read with, and then the ones each edit answers with', async () => {
    expect(catalogs.issuesOf(41)).toEqual([]);

    const read = catalogs.find(41);
    backend.expectOne('/api/catalogs/41').flush({ snapshot: { revision: 4 }, issues: [noTrims] });
    await read;
    expect(catalogs.issuesOf(41)).toEqual([noTrims]);

    const saved = catalogs.addTrims(41, 4, [1]);
    backend
      .expectOne({ method: 'POST', url: '/api/catalogs/41/trims' })
      .flush({ revision: 5, issues: [] });
    await saved;
    expect(catalogs.issuesOf(41)).toEqual([]);
    expect(catalogs.issuesOf(42)).toEqual([]);
  });

  it('does not let an answer that arrives late put older issues back', async () => {
    const earlier = catalogs.setCells(41, 4, [manualAvailable]);
    const later = catalogs.setCells(41, 5, [manualAvailable]);
    const [slow, quick] = backend.match({ method: 'PUT', url: '/api/catalogs/41/cells' });

    quick.flush({ revision: 6, issues: [] });
    await later;
    slow.flush({ revision: 5, issues: [noTrims] });
    await earlier;

    expect(catalogs.issuesOf(41)).toEqual([]);
  });

  it('takes a catalog to have no issues when the backend names none', async () => {
    const saved = catalogs.setCells(41, 4, [manualAvailable]);
    backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' }).flush({ revision: 5 });
    await saved;

    expect(catalogs.issuesOf(41)).toEqual([]);
  });

  it('sends each edit of trims, regions, and offerings as one made from a revision', async () => {
    const edits: [Promise<number>, string, string, unknown][] = [
      [catalogs.addTrims(41, 4, [1, 2]), 'POST', '/api/catalogs/41/trims', { trimIds: [1, 2] }],
      [catalogs.removeTrim(41, 4, 2), 'DELETE', '/api/catalogs/41/trims/2', null],
      [
        catalogs.addRegions(41, 4, ['EU']),
        'POST',
        '/api/catalogs/41/regions',
        { regionCodes: ['EU'] },
      ],
      [catalogs.removeRegion(41, 4, 'EU'), 'DELETE', '/api/catalogs/41/regions/EU', null],
      [
        catalogs.sellIn(41, 4, 2, ['NA', 'EU']),
        'PUT',
        '/api/catalogs/41/trims/2/regions',
        { regionCodes: ['NA', 'EU'] },
      ],
    ];

    for (const [saved, method, url, body] of edits) {
      const request = backend.expectOne({ method, url });
      expect(request.request.headers.get('If-Match')).toBe('"4"');
      expect(request.request.body).toEqual(body);
      request.flush({ revision: 5 });
      await expect(saved).resolves.toBe(5);
    }
  });

  it('gives up a save that has gone unanswered for too long, and takes it back', async () => {
    vi.useFakeTimers();
    const saved = catalogs.setCells(41, 4, [manualAvailable]);
    const outcome = saved.then(
      () => 'saved',
      () => 'failed',
    );
    const request = backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/cells' });

    await vi.advanceTimersByTimeAsync(SAVE_PATIENCE - 1);
    expect(request.cancelled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);

    expect(await outcome).toBe('failed');
    expect(request.cancelled).toBe(true);
  });
});

describe('startPointInWords', () => {
  it("names the lineage's own Approved version", () => {
    expect(startPointInWords({ kind: 'COPY', modelYear: 2027, versionNumber: 3 })).toBe(
      'Starts from Approved v3',
    );
  });

  it('names the earlier model year of a carryover', () => {
    expect(startPointInWords({ kind: 'CARRYOVER', modelYear: 2026, versionNumber: 2 })).toBe(
      'Starts from 2026 Approved v2 (carryover)',
    );
  });

  it('says when there is nothing to start from', () => {
    expect(startPointInWords({ kind: 'EMPTY' })).toBe('Starts empty');
  });
});
