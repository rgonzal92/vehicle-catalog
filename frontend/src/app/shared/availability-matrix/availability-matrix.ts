import {
  afterNextRender,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { TableModule } from 'primeng/table';
import { Named } from '../../core/fixed-lists';
import {
  Availability,
  AVAILABILITY_NAMES,
  Cell,
  FeatureRow,
  MatrixContents,
  MatrixRegion,
  matrixRows,
  MatrixRow,
  MatrixTrim,
  offeringsByRegion,
} from './matrix';

/** An offering as the matrix shows it: a trim sold in a region, and how its cells look. */
interface ShownOffering {
  key: string;
  trim: MatrixTrim;
  region: MatrixRegion;
  /** The cells' classes. A region's first offering carries the line that divides the regions. */
  classes: string;
}

/** What a cell shows for each availability. Not offered is written N and shown as a dash. */
const SYMBOLS: Record<Availability, string> = { S: 'S', A: 'A', N: '-' };

/** The keys that set a focused cell directly. */
const KEYS: Record<string, Availability> = { s: 'S', a: 'A', '-': 'N' };

const cellKey = (featureId: number, trimId: number, regionCode: string) =>
  `${featureId}:${trimId}:${regionCode}`;

const keyOf = (feature: FeatureRow, offering: ShownOffering) =>
  cellKey(feature.id, offering.trim.id, offering.region.code);

/**
 * A catalog's matrix: one row per feature row under category subheaders, and one column per
 * offering under a two-level header of regions and the trims sold in each. Rows are drawn only
 * while they are in view, so the largest catalog stays responsive.
 *
 * In editable mode a cell is set by typing S, A, or - on it, or from the dropdown that Enter or a
 * click opens. The matrix shows the new value and reports the change; it saves nothing itself.
 * Setting a cell to what it already is changes nothing, and the matrix says so. Its
 * host tells it how the save of each change went, and a cell whose save failed goes back to what
 * was last saved and is marked, with the reason. Each feature row has a button that asks for the
 * row to be removed. The Tab key stops at the matrix once, and the arrow keys move between its
 * cells and, left of a row's first cell, to that button.
 *
 * The host element decides the height, and the matrix scrolls inside it.
 */
@Component({
  imports: [TableModule],
  selector: 'app-availability-matrix',
  host: { class: 'block', '(focusin)': 'focusArrived($event)' },
  template: `
    <p-table
      size="small"
      scrollHeight="flex"
      [value]="rows()"
      [scrollable]="true"
      [virtualScroll]="true"
      [virtualScrollItemSize]="rowHeight"
      [virtualScrollOptions]="scrolling"
      [rowTrackBy]="rowIdentity"
    >
      <ng-template #caption>
        <p class="text-sm font-normal">
          S is Standard, A is Available, and - is Not offered.
          <span class="ml-2" aria-live="polite" data-note>{{ note() }}</span>
        </p>
      </ng-template>
      <ng-template #header>
        <tr>
          <th pFrozenColumn scope="col" rowspan="2" [class]="codeCell">Code</th>
          <th pFrozenColumn scope="col" rowspan="2" [class]="nameCell">Feature</th>
          @for (group of byRegion(); track group.region.code) {
            <th scope="colgroup" [class]="regionStart" [attr.colspan]="group.trims.length">
              <span [class]="regionName">{{ group.region.name }}</span>
            </th>
          }
        </tr>
        <tr>
          @for (offering of offerings(); track offering.key) {
            <th scope="col" [class]="offering.classes" [title]="offering.trim.name">
              {{ offering.trim.name }}
            </th>
          }
        </tr>
      </ng-template>
      <ng-template #body let-row>
        @if (row.category; as category) {
          <tr [style.height.px]="rowHeight">
            <td pFrozenColumn colspan="2" class="bg-emphasis py-0 font-semibold">
              {{ category.name }}
            </td>
            @if (offerings().length > 0) {
              <td class="bg-emphasis py-0" [attr.colspan]="offerings().length"></td>
            }
          </tr>
        } @else {
          <tr [style.height.px]="rowHeight">
            <td pFrozenColumn [class]="codeCell">{{ row.feature.code }}</td>
            <td
              pFrozenColumn
              role="rowheader"
              [class]="nameCell"
              [title]="row.feature.name"
              [attr.aria-label]="row.feature.name"
            >
              @if (editable()) {
                <span class="flex items-center gap-1">
                  <span class="truncate">{{ row.feature.name }}</span>
                  <button
                    type="button"
                    tabindex="-1"
                    class="ml-auto size-6 shrink-0 cursor-pointer rounded text-muted-color hover:text-red-700 dark:hover:text-red-400"
                    [attr.data-remove]="row.feature.id"
                    [attr.aria-label]="'Remove ' + row.feature.name"
                    [title]="'Remove ' + row.feature.name"
                    (click)="askToRemove(row.feature)"
                    (keydown)="onRemoveKey($event)"
                  >
                    ×
                  </button>
                </span>
              } @else {
                {{ row.feature.name }}
              }
            </td>
            @for (offering of offerings(); track offering.key) {
              @let failure = whyNotSaved(row.feature, offering);
              <td
                #cell
                [class]="failure ? offering.classes + notSavedCell : offering.classes"
                [attr.title]="failure"
                [attr.tabindex]="editable() ? -1 : null"
                (keydown)="onKey($event, row.feature, offering)"
                (click)="edit($event)"
              >
                @if (editable() && editing() === cell) {
                  <select
                    class="w-full rounded border border-(--p-form-field-border-color) bg-(--p-form-field-background) text-center"
                    [attr.aria-label]="
                      row.feature.name + ', ' + offering.trim.name + ' in ' + offering.region.name
                    "
                    (change)="choose($event, row.feature, offering)"
                    (blur)="editing.set(null)"
                  >
                    @for (availability of availabilities; track availability) {
                      <option
                        [value]="availability"
                        [selected]="availability === valueOf(row.feature, offering)"
                      >
                        {{ symbols[availability] }}
                      </option>
                    }
                  </select>
                } @else {
                  {{ symbols[valueOf(row.feature, offering)] }}
                }
                @if (failure) {
                  <span aria-hidden="true">!</span>
                  <span class="sr-only">{{ failure }}</span>
                }
              </td>
            }
          </tr>
        }
      </ng-template>
    </p-table>
  `,
})
export class AvailabilityMatrix {
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;

  /**
   * The trims, regions, offerings, feature rows, and cells to show. New contents replace everything
   * the matrix shows, including the cells a person has set since the last ones were given.
   */
  readonly contents = input.required<MatrixContents>();

  /** The categories in display order, which is the order of the subheaders. */
  readonly categories = input.required<Named[]>();

  /** Whether cells can be set. A read-only matrix cannot be focused or changed. */
  readonly editable = input(false);

  /**
   * The codes of the regions whose offerings are not shown, to keep a wide matrix narrow. Hiding a
   * region changes nothing about the contents.
   */
  readonly hiddenRegions = input<ReadonlySet<string>>(new Set());

  /**
   * Which feature rows are shown, to narrow a long matrix to the rows being worked on. A row left
   * out is still part of the contents, and keeps what was set in it.
   */
  readonly featureFilter = input<(feature: FeatureRow) => boolean>(everyFeature);

  /** The cell a person just set, with its new availability. */
  readonly cellChange = output<Cell>();

  /**
   * The feature row a person asks to have removed, with how many Standard and Available cells it
   * has. The matrix removes nothing itself.
   */
  readonly featureRemove = output<{ feature: FeatureRow; cells: number }>();

  /** Every row has this height in pixels, which is how the table knows which rows are in view. */
  protected readonly rowHeight = 36;

  /**
   * How many rows are kept drawn beyond each edge of the view. The table's own choice, half the
   * rows in view, draws a dozen rows of the widest matrix at once, which shows as a pause.
   */
  protected readonly scrolling = { numToleratedItems: 4 };

  // The frozen code and name cells are 13rem and 16rem wide. With a little room to spare, 30rem is
  // where the offerings begin, however far the matrix has scrolled sideways.
  protected readonly codeCell = 'w-52 min-w-52 max-w-52 truncate py-0';
  protected readonly nameCell = 'w-64 min-w-64 max-w-64 truncate py-0';

  /** A region's name stays beside the frozen cells while any of its offerings is in view. */
  protected readonly regionName = 'sticky left-[30rem]';

  /** The line that divides one region's offerings from the region before. */
  protected readonly regionStart = 'border-l border-surface';

  /**
   * A cell that takes the focus is scrolled into view clear of the header and the frozen cells,
   * which would otherwise cover it.
   */
  private readonly offeringCell =
    'w-24 min-w-24 max-w-24 truncate py-0 text-center scroll-mt-16 scroll-ml-[30rem]';

  /**
   * How a cell looks while it is marked: red, and with an exclamation mark after its value, so the
   * mark does not rest on colour alone.
   */
  protected readonly notSavedCell =
    ' bg-red-100 font-semibold text-red-900 dark:bg-red-950 dark:text-red-200';

  protected readonly symbols = SYMBOLS;
  protected readonly availabilities = Object.keys(SYMBOLS) as Availability[];

  protected readonly byRegion = computed(() =>
    offeringsByRegion(this.contents()).filter(
      ({ region }) => !this.hiddenRegions().has(region.code),
    ),
  );

  protected readonly offerings = computed<ShownOffering[]>(() =>
    this.byRegion().flatMap(({ region, trims }) =>
      trims.map((trim, index) => ({
        key: `${trim.id}:${region.code}`,
        trim,
        region,
        classes: index === 0 ? `${this.offeringCell} ${this.regionStart}` : this.offeringCell,
      })),
    ),
  );

  protected readonly rows = computed(() => {
    const shown = this.featureFilter();
    const marked = this.markedFeatures();

    // A row with a marked cell is shown whatever the filter, so that the mark is not lost from view.
    return matrixRows(
      this.contents().featureRows.filter((feature) => shown(feature) || marked.has(feature.id)),
      this.categories(),
    );
  });

  /** The Standard and Available cells of the contents by key. */
  private readonly given = computed(
    () =>
      new Map(
        this.contents().cells.map((cell) => [
          cellKey(cell.featureId, cell.trimId, cell.regionCode),
          cell.availability,
        ]),
      ),
  );

  /** The Standard and Available cells by key. It follows the contents and holds a person's edits. */
  private readonly cells = linkedSignal(() => new Map(this.given()));

  /**
   * The cells as they were last saved: the contents, and every edit since whose save worked. It is
   * what a cell goes back to when its save fails. Nothing is drawn from it, so it is changed in
   * place.
   */
  private readonly lastSaved = linkedSignal(() => new Map(this.given()));

  /**
   * How many changes of each cell have been reported and not yet answered with how their save
   * went, by key. It is changed in place, like the cells as last saved.
   */
  private readonly awaited = linkedSignal(() => {
    this.contents();
    return new Map<string, number>();
  });

  /** What each marked cell says about the save that failed, by key. New contents clear the marks. */
  private readonly failures = linkedSignal(() => {
    this.contents();
    return new Map<string, string>();
  });

  protected readonly rowIdentity = (_: number, row: MatrixRow) =>
    row.feature?.id ?? row.category?.code;

  /**
   * The cell whose dropdown is open, or null while none is. It is the cell as drawn, so a dropdown
   * does not come back when its row is scrolled out of view and drawn again.
   */
  protected readonly editing = signal<HTMLElement | null>(null);

  /** The cell that last had the focus, which is where the Tab key returns to. */
  private lastFocused: HTMLElement | null = null;

  protected valueOf(feature: FeatureRow, offering: ShownOffering): Availability {
    return this.cells().get(keyOf(feature, offering)) ?? 'N';
  }

  /**
   * What the matrix last told the person who tried to set a cell to the availability it already
   * has: that there is nothing new to save. It goes when a cell is set, and with new contents.
   */
  protected readonly note = linkedSignal(() => {
    this.contents();
    return '';
  });

  /** The ids of the feature rows that have a marked cell. */
  private readonly markedFeatures = computed(
    () => new Set(Array.from(this.failures().keys(), (key) => Number(key.split(':')[0]))),
  );

  /** What the cell's mark says about the save that failed, or null when the cell is not marked. */
  protected whyNotSaved(feature: FeatureRow, offering: ShownOffering): string | null {
    const failures = this.failures();

    // Hardly ever is any cell marked, and then there is no key to build for every cell drawn.
    return failures.size === 0 ? null : (failures.get(keyOf(feature, offering)) ?? null);
  }

  /**
   * Takes note that the save of a change the matrix reported worked, so the cell has a newer value
   * to go back to.
   */
  saved({ featureId, trimId, regionCode, availability }: Cell): void {
    const key = cellKey(featureId, trimId, regionCode);

    this.answered(key);
    this.put(this.lastSaved(), key, availability);
  }

  /**
   * Takes note that the save of a change the matrix reported failed. The cell goes back to what was
   * last saved and is marked with what is said about the failure, until it is set again. While a
   * later change of the same cell is still on its way, the cell is left alone: that change decides
   * what the cell ends up showing.
   */
  notSaved({ featureId, trimId, regionCode }: Cell, whatToSay: string): void {
    const key = cellKey(featureId, trimId, regionCode);
    if (this.answered(key) > 0) {
      return;
    }

    this.cells.update((cells) => this.put(new Map(cells), key, this.lastSaved().get(key) ?? 'N'));
    this.failures.update((failures) => new Map(failures).set(key, whatToSay));
  }

  /** Counts one change of the cell as answered, and says how many are still on their way. */
  private answered(key: string): number {
    const left = Math.max((this.awaited().get(key) ?? 0) - 1, 0);
    this.awaited().set(key, left);

    return left;
  }

  /** Sets a cell in a map of the Standard and Available ones, where Not offered is no entry. */
  private put(
    cells: Map<string, Availability>,
    key: string,
    availability: Availability,
  ): Map<string, Availability> {
    if (availability === 'N') {
      cells.delete(key);
    } else {
      cells.set(key, availability);
    }
    return cells;
  }

  /**
   * The Tab key stops once at the matrix: on the table's scrolling area, which is also how the
   * keyboard scrolls a read-only matrix. When the keyboard brings the focus there from outside an
   * editable matrix, it moves on to the cell last used, or to the first. Coming back from a cell,
   * it rests there, so that Shift and Tab lead out.
   */
  protected focusArrived(event: FocusEvent): void {
    const arrivedAt = event.target as HTMLElement;
    const cell = arrivedAt.closest<HTMLElement>('td[tabindex]');
    if (cell) {
      this.lastFocused = cell;
      return;
    }
    if (arrivedAt.closest('button[data-remove]')) {
      // A row's remove button was given the focus on purpose, and keeps it.
      return;
    }

    const fromOutside = !this.host.contains(event.relatedTarget as Node | null);
    if (this.editable() && fromOutside && arrivedAt.matches(':focus-visible')) {
      const drawn = this.lastFocused?.isConnected ? this.lastFocused : null;
      (drawn ?? this.firstStop())?.focus();
    }
  }

  /**
   * Puts the focus in the matrix: on the button that removes the feature row, when one is given and
   * it is drawn, or else where the keyboard starts in the matrix.
   */
  focusOn(feature?: FeatureRow): void {
    const button =
      feature && this.host.querySelector<HTMLElement>(`button[data-remove="${feature.id}"]`);

    (button || this.firstStop())?.focus();
  }

  /**
   * Where the keyboard starts in an editable matrix: its first cell, or the first row's remove
   * button when the matrix shows no offering and so has no cells.
   */
  private firstStop(): HTMLElement | null {
    return (
      this.host.querySelector<HTMLElement>('td[tabindex]') ??
      this.host.querySelector<HTMLElement>('button[data-remove]')
    );
  }

  /**
   * Keys on a focused cell: S, A, or - sets it, Enter opens its dropdown, and the arrows move to
   * the cell beside it. Keys typed in the open dropdown are the dropdown's own, except that Enter
   * and Escape close it. A key held with Ctrl, Alt, or the command key is a shortcut of the
   * browser's, and is left to it.
   */
  protected onKey(event: KeyboardEvent, feature: FeatureRow, offering: ShownOffering): void {
    const cell = event.currentTarget as HTMLTableCellElement;
    if (!this.editable() || event.ctrlKey || event.altKey || event.metaKey) {
      return;
    }
    if (event.target !== cell) {
      if (event.key === 'Enter' || event.key === 'Escape') {
        cell.focus();
      }
      return;
    }

    const availability = KEYS[event.key.toLowerCase()];
    let handled = true;
    if (availability) {
      this.set(feature, offering, availability);
    } else if (event.key === 'Enter') {
      this.edit(event);
    } else {
      handled = focusBeside(cell, event.key);
    }
    if (handled) {
      event.preventDefault();
    }
  }

  /** Reports that the person asks to have the feature row removed. */
  protected askToRemove(feature: FeatureRow): void {
    const ofTheRow = `${feature.id}:`;
    this.featureRemove.emit({
      feature,
      cells: Array.from(this.cells().keys()).filter((key) => key.startsWith(ofTheRow)).length,
    });
  }

  /**
   * Keys on a row's remove button, which the left arrow reaches from the row's first cell: the
   * right arrow leads back, and up and down lead to the buttons of the rows above and below.
   */
  protected onRemoveKey(event: KeyboardEvent): void {
    const row = (event.currentTarget as HTMLElement).closest('tr');
    const removeOf = (other: Element | null | undefined) =>
      other?.querySelector('button[data-remove]');
    const beside =
      event.key === 'ArrowRight'
        ? row?.querySelector('td[tabindex]')
        : removeOf(rowBeside(row, event.key, (other) => !!removeOf(other)));

    if (beside instanceof HTMLElement) {
      beside.focus();
      event.preventDefault();
    }
  }

  /** Opens the cell's dropdown and, where the browser can, shows its choices at once. */
  protected edit(event: Event): void {
    const cell = event.currentTarget as HTMLElement;
    if (!this.editable() || event.target !== cell) {
      return;
    }
    this.editing.set(cell);
    afterNextRender(
      () => {
        const dropdown = cell.querySelector('select');
        dropdown?.focus();
        try {
          dropdown?.showPicker();
        } catch {
          // Without the picker the dropdown still opens with a click or the keyboard.
        }
      },
      { injector: this.injector },
    );
  }

  /** A choice is final: it sets the cell and closes the dropdown. */
  protected choose(event: Event, feature: FeatureRow, offering: ShownOffering): void {
    const dropdown = event.target as HTMLSelectElement;
    this.set(feature, offering, dropdown.value as Availability);
    dropdown.closest('td')?.focus();
  }

  private set(feature: FeatureRow, offering: ShownOffering, availability: Availability): void {
    if (!this.editable()) {
      return;
    }
    if (this.valueOf(feature, offering) === availability) {
      this.note.set(
        `No new changes: ${feature.name}, ${offering.trim.name} in ${offering.region.name} is ` +
          `already ${AVAILABILITY_NAMES[availability]}.`,
      );
      return;
    }
    const key = keyOf(feature, offering);
    this.note.set('');

    this.cells.update((cells) => this.put(new Map(cells), key, availability));
    this.awaited().set(key, (this.awaited().get(key) ?? 0) + 1);
    if (this.failures().has(key)) {
      this.failures.update((failures) => {
        const left = new Map(failures);
        left.delete(key);
        return left;
      });
    }
    this.cellChange.emit({
      featureId: feature.id,
      trimId: offering.trim.id,
      regionCode: offering.region.code,
      availability,
    });
  }
}

/** The filter that lets every feature row through. */
const everyFeature = () => true;

/**
 * Whether an element of the table can take the focus, which the cells of the offerings and the
 * buttons that remove rows can.
 */
const focusable = (cell: Element | null | undefined): cell is HTMLElement =>
  cell instanceof HTMLElement && cell.hasAttribute('tabindex');

/**
 * The nearest row above or below a row, as the up or the down arrow asks, that passes the test.
 * Category subheaders lie between the feature rows and pass none.
 */
function rowBeside(
  row: Element | null | undefined,
  key: string,
  wanted: (row: Element) => boolean,
): Element | null {
  if (key !== 'ArrowUp' && key !== 'ArrowDown') {
    return null;
  }
  const step = key === 'ArrowUp' ? 'previousElementSibling' : 'nextElementSibling';
  let other = row?.[step] ?? null;
  while (other && !wanted(other)) {
    other = other[step];
  }
  return other;
}

/**
 * Moves the focus from a cell to the one an arrow key points at, and says whether it did. Up and
 * down step over the category subheaders that lie between the feature rows.
 */
function focusBeside(cell: HTMLTableCellElement, key: string): boolean {
  let beside: Element | null | undefined;

  switch (key) {
    case 'ArrowLeft':
      // Left of a row's first cell is the button that removes the row.
      beside = focusable(cell.previousElementSibling)
        ? cell.previousElementSibling
        : cell.parentElement?.querySelector('button[data-remove]');
      break;
    case 'ArrowRight':
      beside = cell.nextElementSibling;
      break;
    case 'ArrowUp':
    case 'ArrowDown':
      beside = rowBeside(cell.parentElement, key, (row) => focusable(row.children[cell.cellIndex]))
        ?.children[cell.cellIndex];
      break;
  }

  if (!focusable(beside)) {
    return false;
  }
  beside.focus();
  return true;
}
