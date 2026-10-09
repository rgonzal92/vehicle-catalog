import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { GlobalRulesPage } from './global-rules-page';

describe('GlobalRulesPage', () => {
  let backend: HttpTestingController;

  const tow = { id: 5, code: 'PACKAGE_TOW', name: 'Tow Package' };
  const cooling = { id: 6, code: 'COOLING_HEAVY_DUTY', name: 'Heavy-Duty Cooling' };
  const hitch = { id: 7, code: 'TOW_HITCH_RECEIVER', name: 'Trailer Hitch Receiver' };
  const rules = [
    {
      id: 1,
      kind: 'REQUIRES',
      source: tow,
      targets: [cooling],
      allRegions: true,
      regions: [],
      pairKey: null,
    },
    {
      id: 2,
      kind: 'INCLUDES',
      source: tow,
      targets: [hitch, cooling],
      allRegions: false,
      regions: [{ code: 'EU', name: 'Europe' }],
      pairKey: null,
    },
  ];
  const roof = { id: 8, code: 'ROOF_PANORAMIC', name: 'Panoramic Roof' };
  const removable = { id: 9, code: 'ROOF_REMOVABLE', name: 'Removable Roof' };
  const pair = [
    {
      id: 3,
      kind: 'EXCLUDES',
      source: roof,
      targets: [removable],
      allRegions: true,
      regions: [],
      pairKey: 'a-pair',
    },
    {
      id: 4,
      kind: 'EXCLUDES',
      source: removable,
      targets: [roof],
      allRegions: true,
      regions: [],
      pairKey: 'a-pair',
    },
  ];
  const features = [tow, cooling, hitch].map((feature) => ({
    ...feature,
    categoryCode: feature === tow ? 'PACKAGES' : 'CHASSIS',
    kind: feature === tow ? 'PACKAGE' : 'FEATURE',
  }));
  const regions = [
    { code: 'NA', name: 'North America', sortOrder: 1, active: true },
    { code: 'EU', name: 'Europe', sortOrder: 2, active: true },
  ];

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

  async function page(listed: object[] | number = rules): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(GlobalRulesPage);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const read = backend.expectOne('/api/global-rules');
    if (typeof listed === 'number') {
      read.flush(null, { status: listed, statusText: 'Server Error' });
      await vi.waitFor(() => expect(element.querySelector('[role="alert"]')).not.toBeNull());
    } else {
      read.flush(listed);
      await vi.waitFor(() => expect(element.querySelector('tbody tr')).not.toBeNull());
    }

    return element;
  }

  /** Answers what the dialog reads the first time it opens: the features and the regions. */
  async function whatARuleNames(): Promise<void> {
    (await vi.waitFor(() => backend.expectOne((request) => request.url === '/api/features'))).flush(
      { items: features, total: features.length },
    );
    backend.expectOne('/api/regions').flush(regions);
  }

  beforeEach(() => {
    // A dropdown asks how wide the screen is before it opens, which the test page cannot say.
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    backend.verify();
  });

  it('lists each rule with its kind, its targets, and where it holds', async () => {
    expect(rows(await page())).toEqual([
      ['Tow Package', 'Requires', 'Heavy-Duty Cooling', 'Every region', 'EditDelete'],
      [
        'Tow Package',
        'Includes',
        'Trailer Hitch Receiver, Heavy-Duty Cooling',
        'Europe',
        'EditDelete',
      ],
    ]);
  });

  it('says so when there are no rules', async () => {
    const element = await page([]);

    expect(element.textContent).toContain('There are no global rules.');
  });

  it('says so when the rules cannot be read, and reads them again when asked', async () => {
    const element = await page(500);
    expect(element.textContent).toContain('The global rules could not be read.');

    button(element, 'Try again').click();
    (await vi.waitFor(() => backend.expectOne('/api/global-rules'))).flush(rules);

    await vi.waitFor(() => expect(rows(element)).toHaveLength(2));
  });

  it('cannot save a new rule until it has a source and enough targets', async () => {
    const element = await page();

    button(element, 'Add global rule').click();
    await whatARuleNames();

    await vi.waitFor(() => expect(dialog()?.textContent).toContain('Add global rule'));
    expect(button(dialog()!, 'Save').disabled).toBe(true);
    expect(dialog()!.textContent).toContain('Choose 1 to 20 features.');
  });

  it('changes a rule to what the dialog holds, and never its kind', async () => {
    const element = await page();

    button(
      element,
      'Edit the rule: Tow Package includes Trailer Hitch Receiver, Heavy-Duty Cooling',
    ).click();
    await whatARuleNames();
    await vi.waitFor(() => expect(dialog()?.textContent).toContain('Edit global rule'));
    expect(dialog()!.textContent).toContain("A rule's kind cannot be changed.");
    await vi.waitFor(() => expect(button(dialog()!, 'Save').disabled).toBe(false));

    button(dialog()!, 'Save').click();

    const saved = await vi.waitFor(() =>
      backend.expectOne({ method: 'PUT', url: '/api/global-rules/2' }),
    );
    expect(saved.request.body).toEqual({
      kind: 'INCLUDES',
      sourceFeatureId: 5,
      targetFeatureIds: [7, 6],
      allRegions: false,
      regionCodes: ['EU'],
    });
    saved.flush(rules[1]);
    (await vi.waitFor(() => backend.expectOne('/api/global-rules'))).flush(rules);
    await vi.waitFor(() => expect(dialog()).toBeNull());
  });

  it('keeps the dialog open with the reason when the backend refuses a rule', async () => {
    const element = await page();
    button(element, 'Edit the rule: Tow Package requires Heavy-Duty Cooling').click();
    await whatARuleNames();
    await vi.waitFor(() => expect(button(dialog()!, 'Save').disabled).toBe(false));

    button(dialog()!, 'Save').click();
    (
      await vi.waitFor(() => backend.expectOne({ method: 'PUT', url: '/api/global-rules/1' }))
    ).flush(
      { code: 'VALIDATION', detail: 'This rule already exists.' },
      { status: 422, statusText: 'Unprocessable' },
    );

    await vi.waitFor(() => expect(dialog()?.textContent).toContain('This rule already exists.'));
  });

  it('asks before it deletes a rule, and keeps the rule when told to', async () => {
    const element = await page();

    button(element, 'Delete the rule: Tow Package requires Heavy-Duty Cooling').click();

    await vi.waitFor(() =>
      expect(
        dialog()?.querySelector('[data-question]')?.textContent?.replace(/\s+/g, ' '),
      ).toContain('Delete the rule that Tow Package requires Heavy-Duty Cooling?'),
    );
    button(dialog()!, 'Keep').click();
    await vi.waitFor(() => expect(dialog()).toBeNull());
    expect(rows(element)).toHaveLength(2);
  });

  it('deletes a rule and reads the rules again', async () => {
    const element = await page();
    button(element, 'Delete the rule: Tow Package requires Heavy-Duty Cooling').click();
    await vi.waitFor(() => expect(dialog()).not.toBeNull());

    button(dialog()!, 'Delete').click();

    (
      await vi.waitFor(() => backend.expectOne({ method: 'DELETE', url: '/api/global-rules/1' }))
    ).flush(null);
    (await vi.waitFor(() => backend.expectOne('/api/global-rules'))).flush([rules[1]]);
    await vi.waitFor(() => expect(rows(element)).toHaveLength(1));
    expect(dialog()).toBeNull();
  });

  it('marks a paired rule, and highlights both rules of its pair when it is chosen', async () => {
    const element = await page([...rules, ...pair]);
    const highlighted = () =>
      Array.from(element.querySelectorAll('tbody tr'), (row) =>
        row.hasAttribute('data-shown-pair'),
      );
    const show = button(
      element,
      'Show the pair of the rule: Panoramic Roof excludes Removable Roof',
    );
    expect(
      button(element, 'Show the pair of the rule: Tow Package requires Heavy-Duty Cooling'),
    ).toBe(undefined);

    show.click();
    await vi.waitFor(() => expect(highlighted()).toEqual([false, false, true, true]));
    expect(show.getAttribute('aria-pressed')).toBe('true');

    show.click();
    await vi.waitFor(() => expect(highlighted()).toEqual([false, false, false, false]));
  });

  it('says that both rules go when a paired rule is deleted', async () => {
    const element = await page([...rules, ...pair]);

    button(element, 'Delete the rule: Removable Roof excludes Panoramic Roof').click();

    await vi.waitFor(() =>
      expect(
        dialog()?.querySelector('[data-question]')?.textContent?.replace(/\s+/g, ' ').trim(),
      ).toBe(
        'Delete the rule that Removable Roof excludes Panoramic Roof? Its pair, Panoramic Roof' +
          ' excludes Removable Roof, goes with it. Every catalog stops being checked against it.',
      ),
    );
    button(dialog()!, 'Keep').click();
    await vi.waitFor(() => expect(dialog()).toBeNull());
  });

  it('takes one target when a paired rule is changed', async () => {
    const element = await page([...rules, ...pair]);

    button(element, 'Edit the rule: Panoramic Roof excludes Removable Roof').click();
    await whatARuleNames();

    await vi.waitFor(() =>
      expect(dialog()?.textContent).toContain(
        'Choose 1 feature. The pair of this rule changes with it.',
      ),
    );
    button(dialog()!, 'Cancel').click();
    await vi.waitFor(() => expect(dialog()).toBeNull());
  });
});
