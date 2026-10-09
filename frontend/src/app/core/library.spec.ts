import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Library } from './library';

describe('Library', () => {
  it('reads every active feature, a page at a time, until it has them all', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const backend = TestBed.inject(HttpTestingController);
    const feature = (id: number) => ({
      id,
      code: `FEATURE_${id}`,
      name: `Feature ${id}`,
      categoryCode: 'INTERIOR',
      kind: 'FEATURE',
    });
    const first = Array.from({ length: 100 }, (_, index) => feature(index + 1));
    const second = [feature(101), feature(102)];

    const read = TestBed.inject(Library).everyActiveFeature();

    const asked = (page: string) =>
      vi.waitFor(() =>
        backend.expectOne(
          (request) =>
            request.url === '/api/features' &&
            request.params.get('page') === page &&
            request.params.get('size') === '100' &&
            request.params.get('status') === 'ACTIVE',
        ),
      );
    (await asked('0')).flush({ items: first, total: 102 });
    (await asked('1')).flush({ items: second, total: 102 });

    await expect(read).resolves.toHaveLength(102);
    backend.verify();
  });
});
