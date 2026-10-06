import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
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
  featureRows: [
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
  // The test page has no layout, so the table is told what a browser would have measured.
  beforeAll(() => {
    // The table's frozen cells watch their own width with an observer the test page lacks.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        disconnect(): void {}
      },
    );
    // The table starts drawing rows only once it is visible, which it judges by this.
    vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockReturnValue(document.body);
    // It draws the rows that fit its height: at 36 pixels a row, all four of the test's rows.
    vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(400);
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

  const theMatrix = (fixture: ComponentFixture<Host>) =>
    fixture.debugElement.query(By.directive(AvailabilityMatrix))
      .componentInstance as AvailabilityMatrix;

  const texts = (cells: Iterable<Element>) =>
    Array.from(cells).map((cell) => cell.textContent?.trim());

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) => texts(row.children));

  /** The cell of a row of the table for one of the offerings, counted from 0. */
  const cell = (element: HTMLElement, row: number, offering: number) =>
    element.querySelectorAll('tbody tr')[row].children[offering + 2] as HTMLTableCellElement;

  const press = (target: Element, key: string) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));

  it('heads the offerings with their regions and, under each, only the trims sold there', async () => {
    const { element } = await matrix(false);
    const [top, second] = Array.from(element.querySelectorAll('thead tr'));

    expect(texts(top.children)).toEqual(['Code', 'Feature', 'North America', 'Europe']);
    expect(top.children[2].getAttribute('colspan')).toBe('2');
    expect(top.children[3].getAttribute('colspan')).toBe('1');
    expect(texts(second.children)).toEqual(['Base', 'Sport', 'Sport']);
  });

  it('lists feature rows under category subheaders and shows each cell, a missing one as a dash', async () => {
    const { element } = await matrix(false);

    expect(rows(element)).toEqual([
      ['Powertrain', ''],
      ['ENGINE_20T', '2.0L Turbo', 'S', '-', '-'],
      ['Exterior', ''],
      ['ROOF_PANORAMIC', 'Panoramic Roof', '-', 'A', '-'],
    ]);
  });

  it('allows no edits when read-only', async () => {
    const { fixture, element, host } = await matrix(false);
    const first = cell(element, 1, 0);

    expect(element.querySelectorAll('td[tabindex], app-availability-matrix[tabindex]').length).toBe(
      0,
    );
    press(first, 'a');
    press(first, 'Enter');
    first.click();
    await fixture.whenStable();

    expect(first.textContent?.trim()).toBe('S');
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

  it('leaves a key held with Ctrl, Alt, or the command key to the browser', async () => {
    const { fixture, element, host } = await matrix(true);
    const first = cell(element, 1, 1);

    for (const held of [{ ctrlKey: true }, { altKey: true }, { metaKey: true }]) {
      const shortcut = new KeyboardEvent('keydown', {
        key: 'a',
        bubbles: true,
        cancelable: true,
        ...held,
      });
      first.dispatchEvent(shortcut);
      expect(shortcut.defaultPrevented).toBe(false);
    }
    await fixture.whenStable();

    expect(first.textContent?.trim()).toBe('-');
    expect(host.changes).toEqual([]);
  });

  it('opens a dropdown with Enter and sets the cell to the choice made there', async () => {
    const { element, host } = await matrix(true);
    const baseInNorthAmerica = cell(element, 3, 0);

    press(baseInNorthAmerica, 'Enter');
    const dropdown = await vi.waitFor(() => {
      const opened = baseInNorthAmerica.querySelector('select');
      expect(opened).not.toBeNull();
      return opened!;
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
      const opened = first.querySelector('select');
      expect(opened).not.toBeNull();
      return opened!;
    });
    press(dropdown, 'Escape');
    await vi.waitFor(() => expect(first.querySelector('select')).toBeNull());

    expect(first.textContent?.trim()).toBe('S');
    expect(host.changes).toEqual([]);
  });

  it('is one stop for the Tab key, which leads to the first cell and later to the cell last used', async () => {
    const { element } = await matrix(true);
    const tabStop = element.querySelector<HTMLElement>('[tabindex="0"]')!;
    // The test page cannot tell a key press from a click; the focus here comes by keyboard.
    vi.spyOn(tabStop, 'matches').mockReturnValue(true);

    expect(element.querySelectorAll('[tabindex="0"]').length).toBe(1);
    tabStop.focus();
    expect(document.activeElement).toBe(cell(element, 1, 0));

    press(cell(element, 1, 0), 'ArrowRight');
    tabStop.focus();
    expect(document.activeElement, 'coming back from a cell, the focus rests').toBe(tabStop);

    tabStop.blur();
    tabStop.focus();
    expect(document.activeElement).toBe(cell(element, 1, 1));
  });

  it('keeps the focus on the tab stop of a read-only matrix, where the keyboard scrolls it', async () => {
    const { element } = await matrix(false);
    const tabStop = element.querySelector<HTMLElement>('[tabindex="0"]')!;
    vi.spyOn(tabStop, 'matches').mockReturnValue(true);

    tabStop.focus();

    expect(document.activeElement).toBe(tabStop);
  });

  it('closes an open dropdown when the matrix becomes read-only', async () => {
    const { fixture, element, host } = await matrix(true);
    const first = cell(element, 1, 0);
    press(first, 'Enter');
    await vi.waitFor(() => expect(first.querySelector('select')).not.toBeNull());

    host.editable.set(false);
    await fixture.whenStable();

    expect(element.querySelector('select')).toBeNull();
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

  it('puts a cell whose save failed back to what it was, and marks it with the reason', async () => {
    const { fixture, element, host } = await matrix(true);
    const baseTurbo = cell(element, 1, 0);

    press(baseTurbo, 'a');
    await vi.waitFor(() => expect(baseTurbo.textContent?.trim()).toBe('A'));
    theMatrix(fixture).notSaved(host.changes[0], 'Not saved: the catalog changed.');

    await vi.waitFor(() => expect(baseTurbo.title).toBe('Not saved: the catalog changed.'));
    expect(baseTurbo.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'S !Not saved: the catalog changed.',
    );
    expect(baseTurbo.querySelector('.sr-only')?.textContent).toBe(
      'Not saved: the catalog changed.',
    );
    expect(baseTurbo.className).toContain('bg-red-100');
    expect(cell(element, 1, 1).title).toBe('');
  });

  it('goes back to the value of the last save that worked', async () => {
    const { fixture, element, host } = await matrix(true);
    const baseTurbo = cell(element, 1, 0);

    press(baseTurbo, 'a');
    theMatrix(fixture).saved(host.changes[0]);
    press(baseTurbo, '-');
    await vi.waitFor(() => expect(baseTurbo.textContent?.trim()).toBe('-'));
    theMatrix(fixture).notSaved(host.changes[1], 'Not saved.');

    await vi.waitFor(() => expect(baseTurbo.textContent).toContain('A'));
    expect(baseTurbo.title).toBe('Not saved.');
  });

  it('leaves a cell alone when a save of it fails while a later change of it is on its way', async () => {
    const { fixture, element, host } = await matrix(true);
    const baseTurbo = cell(element, 1, 0);

    press(baseTurbo, 'a');
    press(baseTurbo, '-');
    theMatrix(fixture).notSaved(host.changes[0], 'Not saved.');
    theMatrix(fixture).saved(host.changes[1]);
    await fixture.whenStable();

    // The later change was saved, so the cell shows it, unmarked.
    expect(baseTurbo.textContent?.trim()).toBe('-');
    expect(baseTurbo.title).toBe('');
  });

  it('puts a cell back once, to what was saved, when every change of it failed', async () => {
    const { fixture, element, host } = await matrix(true);
    const baseTurbo = cell(element, 1, 0);

    press(baseTurbo, 'a');
    press(baseTurbo, '-');
    theMatrix(fixture).notSaved(host.changes[0], 'Not saved.');
    theMatrix(fixture).notSaved(host.changes[1], 'Not sent.');

    await vi.waitFor(() => expect(baseTurbo.title).toBe('Not sent.'));
    expect(baseTurbo.textContent).toContain('S');
  });

  it('drops the mark from a cell once it is set again', async () => {
    const { fixture, element, host } = await matrix(true);
    const baseTurbo = cell(element, 1, 0);

    press(baseTurbo, 'a');
    theMatrix(fixture).notSaved(host.changes[0], 'Not saved.');
    await vi.waitFor(() => expect(baseTurbo.title).not.toBe(''));
    press(baseTurbo, 'a');

    await vi.waitFor(() => expect(baseTurbo.textContent?.trim()).toBe('A'));
    expect(baseTurbo.title).toBe('');
    expect(baseTurbo.className).not.toContain('bg-red-100');
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
