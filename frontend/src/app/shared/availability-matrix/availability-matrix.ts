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
 * click opens. The matrix shows the new value and reports the change; it saves nothing itself. The
 * Tab key stops at the matrix once, and the arrow keys move between its cells.
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
        <p class="text-sm font-normal">S is Standard, A is Available, and - is Not offered.</p>
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
            <td pFrozenColumn colspan="2" class="bg-surface-100 py-0 font-semibold">
              {{ category.name }}
            </td>
            @if (offerings().length > 0) {
              <td class="bg-surface-100 py-0" [attr.colspan]="offerings().length"></td>
            }
          </tr>
        } @else {
          <tr [style.height.px]="rowHeight">
            <td pFrozenColumn [class]="codeCell">{{ row.feature.code }}</td>
            <td pFrozenColumn role="rowheader" [class]="nameCell" [title]="row.feature.name">
              {{ row.feature.name }}
            </td>
            @for (offering of offerings(); track offering.key) {
              <td
                #cell
                [class]="offering.classes"
                [attr.tabindex]="editable() ? -1 : null"
                (keydown)="onKey($event, row.feature, offering)"
                (click)="edit($event)"
              >
                @if (editable() && editing() === cell) {
                  <select
                    class="w-full rounded border border-surface-400 bg-surface-0 text-center"
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

  /** The cell a person just set, with its new availability. */
  readonly cellChange = output<Cell>();

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
  protected readonly regionStart = 'border-l border-surface-300';

  /**
   * A cell that takes the focus is scrolled into view clear of the header and the frozen cells,
   * which would otherwise cover it.
   */
  private readonly offeringCell =
    'w-24 min-w-24 max-w-24 truncate py-0 text-center scroll-mt-16 scroll-ml-[30rem]';

  protected readonly symbols = SYMBOLS;
  protected readonly availabilities = Object.keys(SYMBOLS) as Availability[];

  protected readonly byRegion = computed(() => offeringsByRegion(this.contents()));

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

  protected readonly rows = computed(() =>
    matrixRows(this.contents().featureRows, this.categories()),
  );

  /** The Standard and Available cells by key. It follows the contents and holds a person's edits. */
  private readonly cells = linkedSignal(
    () =>
      new Map(
        this.contents().cells.map((cell) => [
          cellKey(cell.featureId, cell.trimId, cell.regionCode),
          cell.availability,
        ]),
      ),
  );

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

    const fromOutside = !this.host.contains(event.relatedTarget as Node | null);
    if (this.editable() && fromOutside && arrivedAt.matches(':focus-visible')) {
      const drawn = this.lastFocused?.isConnected ? this.lastFocused : null;
      (drawn ?? this.host.querySelector<HTMLElement>('td[tabindex]'))?.focus();
    }
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
    if (!this.editable() || this.valueOf(feature, offering) === availability) {
      return;
    }
    const key = keyOf(feature, offering);

    this.cells.update((cells) => {
      const changed = new Map(cells);
      if (availability === 'N') {
        changed.delete(key);
      } else {
        changed.set(key, availability);
      }
      return changed;
    });
    this.cellChange.emit({
      featureId: feature.id,
      trimId: offering.trim.id,
      regionCode: offering.region.code,
      availability,
    });
  }
}

/** Whether a cell of the table can take the focus, which the cells of the offerings can. */
const focusable = (cell: Element | null | undefined): cell is HTMLElement =>
  cell instanceof HTMLElement && cell.hasAttribute('tabindex');

/**
 * Moves the focus from a cell to the one an arrow key points at, and says whether it did. Up and
 * down step over the category subheaders that lie between the feature rows.
 */
function focusBeside(cell: HTMLTableCellElement, key: string): boolean {
  let beside: Element | null | undefined;

  switch (key) {
    case 'ArrowLeft':
      beside = cell.previousElementSibling;
      break;
    case 'ArrowRight':
      beside = cell.nextElementSibling;
      break;
    case 'ArrowUp':
    case 'ArrowDown': {
      const step = key === 'ArrowUp' ? 'previousElementSibling' : 'nextElementSibling';
      let row = cell.parentElement?.[step];
      while (row && !focusable(row.children[cell.cellIndex])) {
        row = row[step];
      }
      beside = row?.children[cell.cellIndex];
      break;
    }
  }

  if (!focusable(beside)) {
    return false;
  }
  beside.focus();
  return true;
}
