import {
  afterNextRender,
  Component,
  computed,
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
  MatrixContents,
  MatrixFeature,
  MatrixRegion,
  matrixRows,
  MatrixRow,
  MatrixTrim,
  regionColumns,
} from './matrix';

/** One column of the matrix: a trim sold in a region. */
interface OfferingColumn {
  key: string;
  trim: MatrixTrim;
  region: MatrixRegion;
  /** The cells' classes. The first column of a region carries the line that divides the regions. */
  classes: string;
}

/** What a cell shows for each availability. Not offered is written N and shown as a dash. */
const SYMBOLS: Record<Availability, string> = { S: 'S', A: 'A', N: '-' };

/** The keys that set a focused cell directly. */
const KEYS: Record<string, Availability> = { s: 'S', a: 'A', '-': 'N' };

const cellKey = (featureId: number, trimId: number, regionCode: string) =>
  `${featureId}:${trimId}:${regionCode}`;

/**
 * A catalog's matrix: one row per feature under category subheaders, and one column per offering
 * under a two-level header of regions and the trims sold in each. Rows are drawn only while they
 * are in view, so the largest catalog stays responsive.
 *
 * In editable mode a cell is set by typing S, A, or - on it, or from the dropdown that Enter or a
 * click opens. The matrix shows the new value and reports the change; it saves nothing itself.
 *
 * The host element decides the height, and the matrix scrolls inside it.
 */
@Component({
  imports: [TableModule],
  selector: 'app-availability-matrix',
  host: { class: 'block' },
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
          <th pFrozenColumn scope="col" rowspan="2" [class]="codeColumn">Code</th>
          <th pFrozenColumn scope="col" rowspan="2" [class]="nameColumn">Feature</th>
          @for (group of columns(); track group.region.code) {
            <th scope="colgroup" [class]="regionStart" [attr.colspan]="group.trims.length">
              <!-- The name stays beside the frozen columns while any of the region is in view. -->
              <span class="sticky left-[30rem]">{{ group.region.name }}</span>
            </th>
          }
        </tr>
        <tr>
          @for (column of offerings(); track column.key) {
            <th scope="col" [class]="column.classes" [title]="column.trim.name">
              {{ column.trim.name }}
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
            <td class="bg-surface-100 py-0" [attr.colspan]="offerings().length"></td>
          </tr>
        } @else {
          <tr [style.height.px]="rowHeight">
            <td pFrozenColumn [class]="codeColumn">{{ row.feature.code }}</td>
            <td pFrozenColumn role="rowheader" [class]="nameColumn" [title]="row.feature.name">
              {{ row.feature.name }}
            </td>
            @for (column of offerings(); track column.key) {
              <td
                [class]="column.classes"
                [attr.tabindex]="editable() ? 0 : null"
                (keydown)="onKey($event, row.feature, column)"
                (click)="edit($event, row.feature, column)"
              >
                @if (editing() === keyOf(row.feature, column)) {
                  <select
                    class="w-full rounded border border-surface-400 bg-surface-0 text-center"
                    [attr.aria-label]="
                      row.feature.name + ', ' + column.trim.name + ' in ' + column.region.name
                    "
                    (change)="choose($event, row.feature, column)"
                    (blur)="editing.set(null)"
                  >
                    @for (availability of availabilities; track availability) {
                      <option
                        [value]="availability"
                        [selected]="availability === valueOf(row.feature, column)"
                      >
                        {{ symbols[availability] }}
                      </option>
                    }
                  </select>
                } @else {
                  {{ symbols[valueOf(row.feature, column)] }}
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

  /** The trims, regions, offerings, feature rows, and cells to show. */
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

  protected readonly codeColumn = 'w-52 min-w-52 max-w-52 truncate py-0';
  protected readonly nameColumn = 'w-64 min-w-64 max-w-64 truncate py-0';

  /** The line that divides one region's columns from the region before. */
  protected readonly regionStart = 'border-l border-surface-300';

  /**
   * A cell that takes the focus is scrolled into view clear of the header and the frozen columns,
   * which would otherwise cover it.
   */
  private readonly offeringColumn =
    'w-24 min-w-24 max-w-24 truncate py-0 text-center scroll-mt-16 scroll-ml-[30rem]';

  protected readonly symbols = SYMBOLS;
  protected readonly availabilities = Object.keys(SYMBOLS) as Availability[];

  protected readonly columns = computed(() => regionColumns(this.contents()));

  protected readonly offerings = computed<OfferingColumn[]>(() =>
    this.columns().flatMap(({ region, trims }) =>
      trims.map((trim, index) => ({
        key: `${trim.id}:${region.code}`,
        trim,
        region,
        classes: index === 0 ? `${this.offeringColumn} ${this.regionStart}` : this.offeringColumn,
      })),
    ),
  );

  protected readonly rows = computed(() => matrixRows(this.contents().features, this.categories()));

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

  /** The cell whose dropdown is open, by key, or null while none is. */
  protected readonly editing = signal<string | null>(null);

  protected keyOf(feature: MatrixFeature, column: OfferingColumn): string {
    return cellKey(feature.id, column.trim.id, column.region.code);
  }

  protected valueOf(feature: MatrixFeature, column: OfferingColumn): Availability {
    return this.cells().get(this.keyOf(feature, column)) ?? 'N';
  }

  /**
   * Keys on a focused cell: S, A, or - sets it, Enter or F2 opens its dropdown, and the arrows move
   * to the cell beside it. Keys typed in the open dropdown are the dropdown's own, except that
   * Enter and Escape close it.
   */
  protected onKey(event: KeyboardEvent, feature: MatrixFeature, column: OfferingColumn): void {
    const cell = event.currentTarget as HTMLTableCellElement;
    if (!this.editable()) {
      return;
    }
    if (event.target !== cell) {
      if (event.key === 'Enter' || event.key === 'Escape') {
        cell.focus();
      }
      return;
    }

    const availability = KEYS[event.key.toLowerCase()];
    if (availability) {
      this.set(feature, column, availability);
    } else if (event.key === 'Enter' || event.key === 'F2') {
      this.edit(event, feature, column);
    } else if (!focusBeside(cell, event.key)) {
      return;
    }
    event.preventDefault();
  }

  /** Opens the cell's dropdown and, where the browser can, shows its choices at once. */
  protected edit(event: Event, feature: MatrixFeature, column: OfferingColumn): void {
    const cell = event.currentTarget as HTMLElement;
    if (!this.editable() || event.target !== cell) {
      return;
    }
    this.editing.set(this.keyOf(feature, column));
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
  protected choose(event: Event, feature: MatrixFeature, column: OfferingColumn): void {
    const dropdown = event.target as HTMLSelectElement;
    this.set(feature, column, dropdown.value as Availability);
    dropdown.closest('td')?.focus();
  }

  private set(feature: MatrixFeature, column: OfferingColumn, availability: Availability): void {
    if (this.valueOf(feature, column) === availability) {
      return;
    }
    const cell = { featureId: feature.id, trimId: column.trim.id, regionCode: column.region.code };
    const key = this.keyOf(feature, column);

    this.cells.update((cells) => {
      const changed = new Map(cells);
      if (availability === 'N') {
        changed.delete(key);
      } else {
        changed.set(key, availability);
      }
      return changed;
    });
    this.cellChange.emit({ ...cell, availability });
  }
}

/** Moves the focus from a cell to the cell an arrow key points at, if there is one. */
function focusBeside(cell: HTMLTableCellElement, key: string): boolean {
  const row = cell.parentElement as HTMLTableRowElement;
  const inRow = (other: Element | null) =>
    (other as HTMLTableRowElement | null)?.cells[cell.cellIndex] ?? null;
  // A category subheader lies between the rows of two categories; step over it.
  const across = (step: 'previousElementSibling' | 'nextElementSibling') => {
    let other = row[step];
    while (other && inRow(other)?.tabIndex !== 0) {
      other = other[step];
    }
    return inRow(other);
  };
  const beside: Record<string, () => Element | null> = {
    ArrowLeft: () => cell.previousElementSibling,
    ArrowRight: () => cell.nextElementSibling,
    ArrowUp: () => across('previousElementSibling'),
    ArrowDown: () => across('nextElementSibling'),
  };
  const target = beside[key]?.();

  if (target instanceof HTMLElement && target.tabIndex === 0) {
    target.focus();
    return true;
  }
  return false;
}
