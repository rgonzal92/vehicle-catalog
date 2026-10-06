import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AvailabilityMatrix } from './availability-matrix';
import { Cell, MatrixContents } from './matrix';

const contents: MatrixContents = {
  trims: [
    { id: 1, name: 'Base', sortOrder: 1 },
    { id: 2, name: 'Sport', sortOrder: 2 },
  ],
  regions: [
    { code: 'NA', name: 'North America' },
    { code: 'EU', name: 'Europe' },
  ],
  offerings: [
    { trimId: 1, regionCode: 'NA' },
    { trimId: 2, regionCode: 'NA' },
    { trimId: 2, regionCode: 'EU' },
  ],
  features: [
    { id: 10, code: 'ROOF_PANORAMIC', name: 'Panoramic Roof', categoryCode: 'EXTERIOR' },
    { id: 11, code: 'ENGINE_20T', name: '2.0L Turbo', categoryCode: 'POWERTRAIN' },
  ],
  cells: [
    { featureId: 10, trimId: 2, regionCode: 'NA', availability: 'A' },
    { featureId: 11, trimId: 1, regionCode: 'NA', availability: 'S' },
  ],
};
const categories = [
  { code: 'POWERTRAIN', name: 'Powertrain' },
  { code: 'EXTERIOR', name: 'Exterior' },
];

@Component({
  imports: [AvailabilityMatrix],
  template: `
    <app-availability-matrix
      style="height: 400px"
      [contents]="contents()"
      [categories]="categories"
      [editable]="editable()"
      (cellChange)="changes.push($event)"
    />
  `,
})
class Host {
  readonly contents = signal(contents);
  readonly categories = categories;
  readonly editable = signal(false);
  readonly changes: Cell[] = [];
}

describe('AvailabilityMatrix', () => {
  // The test page has no layout. The table draws only the rows that fit its height, so every
  // element is given a size, and the observer its frozen columns watch their width with is absent.
  beforeAll(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        disconnect(): void {}
      },
    );
    vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockReturnValue(document.body);
    vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(400);
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(800);
  });
  afterAll(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  async function matrix(editable: boolean) {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.editable.set(editable);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => expect(element.querySelectorAll('tbody tr').length).toBe(4));

    return { fixture, element, host: fixture.componentInstance };
  }

  const texts = (cells: Iterable<Element>) =>
    Array.from(cells).map((cell) => cell.textContent?.trim());

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) => texts(row.children));

  /** The cell of a feature row in one of the offering columns, counted from 0. */
  const cell = (element: HTMLElement, row: number, offering: number) =>
    element.querySelectorAll('tbody tr')[row].children[offering + 2] as HTMLTableCellElement;

  const press = (target: Element, key: string) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));

  it('heads the columns with regions and, under each, only the trims sold there', async () => {
    const { element } = await matrix(false);
    const [top, second] = Array.from(element.querySelectorAll('thead tr'));

    expect(texts(top.children)).toEqual(['Code', 'Feature', 'North America', 'Europe']);
    expect(top.children[2].getAttribute('colspan')).toBe('2');
    expect(top.children[3].getAttribute('colspan')).toBe('1');
    expect(texts(second.children)).toEqual(['Base', 'Sport', 'Sport']);
  });

  it('lists features under category subheaders and shows each cell, a missing one as a dash', async () => {
    const { element } = await matrix(false);

    expect(rows(element)).toEqual([
      ['Powertrain', ''],
      ['ENGINE_20T', '2.0L Turbo', 'S', '-', '-'],
      ['Exterior', ''],
      ['ROOF_PANORAMIC', 'Panoramic Roof', '-', 'A', '-'],
    ]);
  });

  it('allows no edits when read-only', async () => {
    const { element, host } = await matrix(false);
    const first = cell(element, 1, 0);

    expect(element.querySelectorAll('td[tabindex]').length).toBe(0);
    press(first, 'a');
    first.click();
    await vi.waitFor(() => expect(first.textContent?.trim()).toBe('S'));

    expect(element.querySelector('select')).toBeNull();
    expect(host.changes).toEqual([]);
  });

  it('sets a focused cell when S, A, or - is typed, and reports each change', async () => {
    const { element, host } = await matrix(true);
    const sportInEurope = cell(element, 1, 2);

    press(sportInEurope, 'a');
    await vi.waitFor(() => expect(sportInEurope.textContent?.trim()).toBe('A'));
    press(sportInEurope, 'S');
    await vi.waitFor(() => expect(sportInEurope.textContent?.trim()).toBe('S'));
    press(sportInEurope, '-');
    await vi.waitFor(() => expect(sportInEurope.textContent?.trim()).toBe('-'));

    const change = { featureId: 11, trimId: 2, regionCode: 'EU' };
    expect(host.changes).toEqual([
      { ...change, availability: 'A' },
      { ...change, availability: 'S' },
      { ...change, availability: 'N' },
    ]);
  });

  it('reports nothing when a key sets the value the cell already has', async () => {
    const { element, host } = await matrix(true);

    press(cell(element, 1, 0), 's');
    press(cell(element, 1, 1), '-');
    press(cell(element, 1, 1), 'x');

    expect(host.changes).toEqual([]);
  });

  it('opens a dropdown with Enter and sets the cell to the choice made there', async () => {
    const { element, host } = await matrix(true);
    const baseInNorthAmerica = cell(element, 3, 0);

    press(baseInNorthAmerica, 'Enter');
    const dropdown = await vi.waitFor(() => {
      const select = baseInNorthAmerica.querySelector('select');
      expect(select).not.toBeNull();
      return select!;
    });
    expect(dropdown.getAttribute('aria-label')).toBe('Panoramic Roof, Base in North America');
    expect(texts(dropdown.options)).toEqual(['S', 'A', '-']);
    expect(dropdown.value).toBe('N');

    dropdown.value = 'S';
    dropdown.dispatchEvent(new Event('change'));
    await vi.waitFor(() => expect(baseInNorthAmerica.textContent?.trim()).toBe('S'));

    expect(baseInNorthAmerica.querySelector('select')).toBeNull();
    expect(host.changes).toEqual([
      { featureId: 10, trimId: 1, regionCode: 'NA', availability: 'S' },
    ]);
  });

  it('opens a dropdown with a click and closes it with Escape, changing nothing', async () => {
    const { element, host } = await matrix(true);
    const first = cell(element, 1, 0);

    first.click();
    const dropdown = await vi.waitFor(() => {
      const select = first.querySelector('select');
      expect(select).not.toBeNull();
      return select!;
    });
    press(dropdown, 'Escape');
    await vi.waitFor(() => expect(first.querySelector('select')).toBeNull());

    expect(first.textContent?.trim()).toBe('S');
    expect(host.changes).toEqual([]);
  });

  it('moves the focus between cells with the arrow keys, stepping over subheaders', async () => {
    const { element } = await matrix(true);
    const start = cell(element, 1, 1);
    start.focus();

    press(start, 'ArrowRight');
    expect(document.activeElement).toBe(cell(element, 1, 2));
    press(cell(element, 1, 2), 'ArrowDown');
    expect(document.activeElement).toBe(cell(element, 3, 2));
    press(cell(element, 3, 2), 'ArrowLeft');
    press(cell(element, 3, 1), 'ArrowUp');
    expect(document.activeElement).toBe(start);
  });

  it('shows the cells of new contents, dropping edits made to the old ones', async () => {
    const { element, host } = await matrix(true);
    press(cell(element, 1, 2), 'a');
    await vi.waitFor(() => expect(cell(element, 1, 2).textContent?.trim()).toBe('A'));

    host.contents.set({ ...contents, cells: [] });

    await vi.waitFor(() =>
      expect(rows(element)[1]).toEqual(['ENGINE_20T', '2.0L Turbo', '-', '-', '-']),
    );
  });
});
