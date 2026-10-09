import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Change } from '../../core/catalogs';
import { changeInWords, HistoryTab, kindInWords } from './history-tab';

const cellSet: Change = {
  id: 9,
  at: '2026-01-09T10:00:00Z',
  actor: 'Demo Author',
  kind: 'CELL_SET',
  featureCode: 'ROOF_PANORAMIC',
  featureName: 'Panoramic Roof',
  trim: 'Sport',
  region: 'Europe',
  oldValue: 'N',
  newValue: 'A',
};
const nothingNamed = {
  featureCode: null,
  featureName: null,
  trim: null,
  region: null,
  oldValue: null,
  newValue: null,
};

describe('HistoryTab', () => {
  let backend: HttpTestingController;

  const changesRequest = () =>
    vi.waitFor(() => backend.expectOne((request) => request.url === '/api/catalogs/41/changes'));

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent?.trim()),
    );

  const headings = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('th')).map((heading) => heading.textContent?.trim());

  /** Renders the history of catalog 41 and answers its first page with these changes. */
  async function history(items: Change[], total = items.length): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(HistoryTab);
    fixture.componentRef.setInput('catalogId', 41);
    fixture.detectChanges();
    const request = await changesRequest();
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('25');
    request.flush({ items, total });
    await fixture.whenStable();

    return fixture.nativeElement as HTMLElement;
  }

  it('lists each change with when, who, its kind, and what changed', async () => {
    const element = await history([cellSet]);

    const [[when, who, kind, what]] = await vi.waitFor(() => {
      expect(rows(element)[0]).toHaveLength(4);
      return rows(element);
    });
    expect(when).toContain('2026');
    expect(who).toBe('Demo Author');
    expect(kind).toBe('Cell set');
    expect(headings(element)).toEqual(['When', 'Who', 'Kind', 'What changed']);
    expect(what).toBe(
      'Panoramic Roof (ROOF_PANORAMIC), Sport in Europe: was Not offered, now Available',
    );
  });

  it('says so when the catalog has no changes', async () => {
    const element = await history([]);

    await vi.waitFor(() =>
      expect(element.textContent).toContain('No changes have been made to this catalog.'),
    );
  });

  it('asks for the next page when the table is paged', async () => {
    const element = await history(Array(25).fill(cellSet), 26);

    const next = await vi.waitFor(() => {
      const button = element.querySelector<HTMLButtonElement>('button.p-paginator-next');
      expect(button?.disabled).toBe(false);
      return button!;
    });
    next.click();

    const asked = await changesRequest();
    expect(asked.request.params.get('page')).toBe('1');
    asked.flush({ items: [{ ...cellSet, id: 1, trim: 'Base' }], total: 26 });
    await vi.waitFor(() => expect(rows(element)).toHaveLength(1));
    expect(rows(element)[0][3]).toContain('Base in Europe');
  });
});

describe('HistoryTab, when the history cannot be read', () => {
  it('says so, stays on the page it shows, and reads again when asked to', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const backend = TestBed.inject(HttpTestingController);
    const changesRequest = () =>
      vi.waitFor(() => backend.expectOne((request) => request.url === '/api/catalogs/41/changes'));
    const fixture = TestBed.createComponent(HistoryTab);
    fixture.componentRef.setInput('catalogId', 41);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    (await changesRequest()).flush({ items: Array(25).fill(cellSet), total: 26 });
    const next = await vi.waitFor(() => {
      const found = element.querySelector<HTMLButtonElement>('button.p-paginator-next');
      expect(found?.disabled).toBe(false);
      return found!;
    });

    next.click();
    (await changesRequest()).flush(null, { status: 503, statusText: 'Unavailable' });

    const again = await vi.waitFor(() => {
      const found = Array.from(element.querySelectorAll('button')).find(
        (candidate) => candidate.textContent?.trim() === 'Try again',
      );
      expect(found).toBeDefined();
      return found!;
    });
    expect(element.textContent).toContain('The change history could not be read.');
    expect(element.querySelectorAll('tbody tr')).toHaveLength(25);

    again.click();
    const retried = await changesRequest();
    expect(retried.request.params.get('page')).toBe('0');
    retried.flush({ items: [cellSet], total: 1 });
    await vi.waitFor(() =>
      expect(element.textContent).not.toContain('The change history could not be read.'),
    );
  });
});

describe('kindInWords', () => {
  it('reads any kind as words', () => {
    expect(kindInWords('CELL_SET')).toBe('Cell set');
    expect(kindInWords('TRIM_REMOVED')).toBe('Trim removed');
    expect(kindInWords('MERGED')).toBe('Merged');
  });
});

describe('changeInWords', () => {
  it('names a trim, a region, or an offering on its own', () => {
    const change = { ...cellSet, ...nothingNamed, kind: 'SOMETHING' };

    expect(changeInWords({ ...change, trim: 'Sport' })).toBe('Sport');
    expect(changeInWords({ ...change, region: 'Europe' })).toBe('Europe');
    expect(changeInWords({ ...change, trim: 'Sport', region: 'Europe' })).toBe('Sport in Europe');
    expect(
      changeInWords({ ...change, featureName: 'Panoramic Roof', featureCode: 'ROOF_PANORAMIC' }),
    ).toBe('Panoramic Roof (ROOF_PANORAMIC)');
  });

  it('says nothing about a change that names nothing', () => {
    expect(changeInWords({ ...cellSet, ...nothingNamed })).toBe('');
  });

  it('reads the values of anything but a cell as they are, such as a name that looks like one', () => {
    const renamed = {
      ...cellSet,
      ...nothingNamed,
      kind: 'RENAMED',
      oldValue: 'S',
      newValue: 'toString',
    };

    expect(changeInWords(renamed)).toBe('was S, now toString');
  });

  it('says a value that stands alone as it is, such as a rule that was added or removed', () => {
    const rule = { ...cellSet, ...nothingNamed, kind: 'RULE_ADDED', oldValue: null };
    const said = 'Tow Package requires Heavy-Duty Cooling (on Sport)';

    expect(changeInWords({ ...rule, newValue: said })).toBe(said);
    expect(changeInWords({ ...rule, kind: 'RULE_REMOVED', oldValue: said, newValue: null })).toBe(
      said,
    );
  });

  it('shows a value it has no name for as it is', () => {
    expect(changeInWords({ ...cellSet, ...nothingNamed, oldValue: 'X', newValue: 'S' })).toBe(
      'was X, now Standard',
    );
  });
});
