import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { Catalog, CatalogEdit, CatalogRule } from '../../core/catalogs';
import { RulesTab } from './rules-tab';

const tow = { id: 5, code: 'PACKAGE_TOW', name: 'Tow Package', kind: 'PACKAGE' };
const cooling = { id: 6, code: 'COOLING_HEAVY_DUTY', name: 'Heavy-Duty Cooling', kind: 'FEATURE' };
const roof = { id: 8, code: 'ROOF_PANORAMIC', name: 'Panoramic Roof', kind: 'FEATURE' };
const leather = { id: 9, code: 'SEAT_LEATHER', name: 'Leather Seats', kind: 'FEATURE' };

const requires: CatalogRule = {
  key: 'requires',
  kind: 'REQUIRES',
  sourceFeatureId: 9,
  targetFeatureIds: [8, 6],
  allTrims: false,
  trimIds: [2],
  allRegions: false,
  regionCodes: ['NA', 'EU'],
  pairKey: null,
};
const excludes: CatalogRule = {
  key: 'excludes',
  kind: 'EXCLUDES',
  sourceFeatureId: 8,
  targetFeatureIds: [9],
  allTrims: true,
  trimIds: [],
  allRegions: true,
  regionCodes: [],
  pairKey: 'a-pair',
};
const mirrored: CatalogRule = {
  ...excludes,
  key: 'mirrored',
  sourceFeatureId: 9,
  targetFeatureIds: [8],
};

/** A working copy with four feature rows, two trims, two regions, and the rules given. */
const catalogWith = (...rules: CatalogRule[]) =>
  ({
    snapshot: {
      catalogId: 41,
      trims: [
        { id: 1, name: 'Base', sortOrder: 1 },
        { id: 2, name: 'Sport', sortOrder: 2 },
      ],
      regions: [
        { code: 'NA', name: 'North America' },
        { code: 'EU', name: 'Europe' },
      ],
      featureRows: [tow, cooling, roof, leather],
      rules,
    },
  }) as unknown as Catalog;

/** Global rules: one that names two feature rows, and one that names none. */
const globalRules = [
  {
    id: 1,
    kind: 'INCLUDES',
    source: tow,
    targets: [cooling],
    allRegions: false,
    regions: [{ code: 'EU', name: 'Europe' }],
    pairKey: null,
  },
  {
    id: 2,
    kind: 'REQUIRES',
    source: { id: 70, code: 'TIRES_WINTER', name: 'Winter Tires' },
    targets: [{ id: 71, code: 'WHEEL_LOCKS', name: 'Wheel Locks' }],
    allRegions: true,
    regions: [],
    pairKey: null,
  },
];

@Component({
  imports: [RulesTab],
  template: `<app-rules-tab [catalog]="catalog()" [run]="run" [editable]="editable()" />`,
})
class Host {
  readonly catalog = signal(catalogWith(requires, excludes, mirrored));
  readonly editable = signal(true);

  /** The edits the tab asked to be sent. Each is sent as an edit of revision 4. */
  readonly sent: Promise<number>[] = [];

  readonly run = async (edit: CatalogEdit): Promise<void> => {
    const sending = edit(4);
    this.sent.push(sending);
    await sending;
  };
}

describe('RulesTab', () => {
  let backend: HttpTestingController;
  let host: Host;

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td'), (cell) => cell.textContent?.trim()),
    );

  const dialog = () => document.querySelector<HTMLElement>('.p-dialog');

  const button = (scope: ParentNode, label: string) =>
    Array.from(scope.querySelectorAll('button')).find(
      (candidate) =>
        candidate.textContent?.trim() === label || candidate.getAttribute('aria-label') === label,
    )!;

  /** Shows the tab and answers its reading of the global rules. */
  async function tab(listed: object[] | number = globalRules): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const read = backend.expectOne('/api/global-rules');
    if (typeof listed === 'number') {
      read.flush(null, { status: listed, statusText: 'Server Error' });
      await vi.waitFor(() => expect(element.querySelector('[role="alert"]')).not.toBeNull());
    } else {
      read.flush(listed);
      await vi.waitFor(() =>
        expect(element.querySelectorAll('tbody tr')).toHaveLength(listed.length ? 4 : 3),
      );
    }

    return element;
  }

  beforeEach(() => {
    // A dropdown asks how wide the screen is before it opens, which the test page cannot say.
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    backend.verify();
  });

  it("lists the catalog's rules with their scopes, then the global rules that name a feature row", async () => {
    expect(rows(await tab())).toEqual([
      [
        'Leather Seats',
        'Requires',
        'Panoramic Roof, Heavy-Duty Cooling',
        'Sport',
        'North America, Europe',
        'Catalog',
        'EditDelete',
      ],
      [
        'Panoramic Roof',
        'Excludes',
        'Leather Seats',
        'Every trim',
        'Every region',
        'Catalog',
        'EditDelete',
      ],
      [
        'Leather Seats',
        'Excludes',
        'Panoramic Roof',
        'Every trim',
        'Every region',
        'Catalog',
        'EditDelete',
      ],
      ['Tow Package', 'Includes', 'Heavy-Duty Cooling', 'Every trim', 'Europe', 'Global', ''],
    ]);
  });

  it('goes on listing the rules of the catalog when the global rules cannot be read', async () => {
    const element = await tab(500);

    expect(element.textContent).toContain('The global rules could not be read.');
    expect(rows(element)).toHaveLength(3);

    button(element, 'Try again').click();
    (await vi.waitFor(() => backend.expectOne('/api/global-rules'))).flush(globalRules);
    await vi.waitFor(() => expect(rows(element)).toHaveLength(4));
  });

  it('says so when there is no rule to list', async () => {
    const element = await tab([]);
    host.catalog.set(catalogWith());

    await vi.waitFor(() =>
      expect(element.textContent).toContain(
        'This catalog has no rules, and no global rule names one of its features.',
      ),
    );
  });

  it('is read-only when the catalog cannot be edited', async () => {
    const element = await tab();

    host.editable.set(false);

    await vi.waitFor(() => expect(button(element, 'Add rule')).toBe(undefined));
    expect(rows(element).map((row) => row.at(-1))).toEqual(['', '', '', '']);
  });

  it('cannot save a new rule until it has a source and enough targets', async () => {
    const element = await tab();

    button(element, 'Add rule').click();

    await vi.waitFor(() => expect(dialog()?.textContent).toContain('Add rule'));
    expect(button(dialog()!, 'Save').disabled).toBe(true);
    expect(dialog()!.textContent).toContain('Choose 1 to 20 feature rows.');
    expect(dialog()!.textContent).toContain('The rule holds on every trim');
  });

  it('changes a rule to what the dialog holds, as an edit of the catalog', async () => {
    const element = await tab();

    button(
      element,
      'Edit the rule: Leather Seats requires Panoramic Roof, Heavy-Duty Cooling' +
        ' (on Sport; in North America, Europe)',
    ).click();
    await vi.waitFor(() => expect(dialog()?.textContent).toContain('Edit rule'));
    expect(dialog()!.textContent).toContain("A rule's kind cannot be changed.");
    await vi.waitFor(() => expect(button(dialog()!, 'Save').disabled).toBe(false));

    button(dialog()!, 'Save').click();

    const saved = await vi.waitFor(() =>
      backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/rules/requires' }),
    );
    expect(saved.request.headers.get('If-Match')).toBe('"4"');
    expect(saved.request.body).toEqual({
      kind: 'REQUIRES',
      sourceFeatureId: 9,
      targetFeatureIds: [8, 6],
      allTrims: false,
      trimIds: [2],
      allRegions: false,
      regionCodes: ['NA', 'EU'],
    });
    saved.flush({ revision: 5, issues: [] });
    await vi.waitFor(() => expect(dialog()).toBeNull());
    expect(await host.sent[0]).toBe(5);
  });

  it('keeps the dialog open with the reason when the backend refuses a rule', async () => {
    const element = await tab();
    button(element, 'Edit the rule: Panoramic Roof excludes Leather Seats').click();
    await vi.waitFor(() =>
      expect(dialog()?.textContent).toContain(
        'Choose 1 feature row. The pair of this rule changes with it.',
      ),
    );
    await vi.waitFor(() => expect(button(dialog()!, 'Save').disabled).toBe(false));

    button(dialog()!, 'Save').click();
    (
      await vi.waitFor(() =>
        backend.expectOne({ method: 'PUT', url: '/api/catalogs/41/rules/excludes' }),
      )
    ).flush(
      { code: 'VALIDATION', detail: 'This rule already exists.' },
      { status: 422, statusText: 'Unprocessable' },
    );

    await vi.waitFor(() => expect(dialog()?.textContent).toContain('This rule already exists.'));
  });

  it('marks a paired rule, and highlights both rules of its pair when it is chosen', async () => {
    const element = await tab();
    const highlighted = () =>
      Array.from(element.querySelectorAll('tbody tr'), (row) =>
        row.hasAttribute('data-shown-pair'),
      );
    const show = button(
      element,
      'Show the pair of the rule: Panoramic Roof excludes Leather Seats',
    );

    show.click();
    await vi.waitFor(() => expect(highlighted()).toEqual([false, true, true, false]));
    expect(show.getAttribute('aria-pressed')).toBe('true');

    show.click();
    await vi.waitFor(() => expect(highlighted()).toEqual([false, false, false, false]));
  });

  it('says that both rules go when a paired rule is deleted, and deletes it when told to', async () => {
    const element = await tab();

    button(element, 'Delete the rule: Leather Seats excludes Panoramic Roof').click();

    await vi.waitFor(() =>
      expect(
        dialog()?.querySelector('[data-question]')?.textContent?.replace(/\s+/g, ' ').trim(),
      ).toBe(
        'Delete the rule that Leather Seats excludes Panoramic Roof? Its pair, Panoramic Roof' +
          ' excludes Leather Seats, goes with it.',
      ),
    );
    button(dialog()!, 'Delete').click();

    const deleted = await vi.waitFor(() =>
      backend.expectOne({ method: 'DELETE', url: '/api/catalogs/41/rules/mirrored' }),
    );
    expect(deleted.request.headers.get('If-Match')).toBe('"4"');
    deleted.flush({ revision: 5, issues: [] });
    await vi.waitFor(() => expect(dialog()).toBeNull());
  });
});
