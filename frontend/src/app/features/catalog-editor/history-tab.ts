import { DatePipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { Catalogs, Change, ChangePage } from '../../core/catalogs';
import { Availability, AVAILABILITY_NAMES } from '../../shared/availability-matrix/matrix';

/** A change's kind in words: `CELL_SET` reads "Cell set". It works for a kind of any name. */
export function kindInWords(kind: string): string {
  const words = kind.toLowerCase().replaceAll('_', ' ');

  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * What a change touched, in words, with the value before and after where it has them: "Panoramic
 * Roof (ROOF_PANORAMIC), Sport in Europe: was Not offered, now Available". It uses whatever the
 * change names, so it reads for every kind of change: a rule that was added reads as the rule. A feature is named with its code, since two
 * features can share a name.
 */
export function changeInWords(change: Change): string {
  const { featureCode, featureName, trim, region, oldValue, newValue } = change;
  const feature = featureName && `${featureName} (${featureCode})`;
  const offering = trim && region ? `${trim} in ${region}` : (trim ?? region);
  const touched = [feature, offering].filter(Boolean).join(', ');
  // Only a cell's values are availabilities. Any other value, such as a name, reads as it is.
  const worded = change.kind === 'CELL_SET' ? availabilityName : (value: string) => value;
  // A change with one value alone, such as a rule that was added, says that value.
  const values =
    oldValue !== null && newValue !== null
      ? `was ${worded(oldValue)}, now ${worded(newValue)}`
      : (newValue ?? oldValue ?? '');

  return [touched, values].filter(Boolean).join(': ');
}

const availabilityName = (value: string) => AVAILABILITY_NAMES[value as Availability] ?? value;

/**
 * A catalog's change history, newest first and a page at a time: when each change was made, by
 * whom, what kind of change it was, and what it touched. There is no undo; this is where the value
 * a cell had before is found. It is read afresh each time it is shown.
 */
@Component({
  imports: [DatePipe, Button, Message, TableModule],
  selector: 'app-history-tab',
  template: `
    @if (failed()) {
      <p-message class="mb-4 block" severity="error">
        <span>The change history could not be read.</span>
        <p-button
          class="ml-4"
          label="Try again"
          severity="secondary"
          size="small"
          (onClick)="load()"
        />
      </p-message>
    }
    <p-table
      class="block"
      size="small"
      [value]="page()?.items ?? []"
      [loading]="loading()"
      [lazy]="true"
      [paginator]="true"
      [rows]="pageSize"
      [totalRecords]="page()?.total ?? 0"
      [(first)]="first"
      (onLazyLoad)="load()"
    >
      <ng-template #header>
        <tr>
          <th scope="col">When</th>
          <th scope="col">Who</th>
          <th scope="col">Kind</th>
          <th scope="col">What changed</th>
        </tr>
      </ng-template>
      <ng-template #body let-change>
        <tr>
          <td>{{ change.at | date: 'medium' }}</td>
          <td>{{ change.actor }}</td>
          <td>{{ kindInWords(change.kind) }}</td>
          <td>{{ changeInWords(change) }}</td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="4">{{ page() ? 'No changes have been made to this catalog.' : '' }}</td>
        </tr>
      </ng-template>
    </p-table>
  `,
})
export class HistoryTab {
  private readonly catalogs = inject(Catalogs);

  readonly catalogId = input.required<number>();

  protected readonly pageSize = 25;

  /** The position of the page's first change among all of the catalog's changes. */
  protected readonly first = signal(0);

  /** The page being shown, or null until the backend has answered. */
  protected readonly page = signal<ChangePage | null>(null);

  /** Whether a page has been asked for and not yet answered. */
  protected readonly loading = signal(true);

  /** Whether the page last asked for could not be read. */
  protected readonly failed = signal(false);

  /** Where the page being shown starts, which is where the table goes back to after a failure. */
  private shownFrom = 0;

  protected readonly kindInWords = kindInWords;
  protected readonly changeInWords = changeInWords;

  /** How many pages have been asked for, so that a slow answer to an earlier one is dropped. */
  private asked = 0;

  /**
   * Shows the page the table is on. The table asks for this on its first display and on paging.
   * When the page cannot be read, the table stays on the page it shows and the tab says so.
   */
  protected async load(): Promise<void> {
    const mine = ++this.asked;
    const from = this.first();
    this.loading.set(true);
    try {
      const page = await this.catalogs.changes(
        this.catalogId(),
        from / this.pageSize,
        this.pageSize,
      );
      if (mine === this.asked) {
        this.page.set(page);
        this.shownFrom = from;
        this.failed.set(false);
      }
    } catch {
      if (mine === this.asked) {
        this.first.set(this.shownFrom);
        this.failed.set(true);
      }
    } finally {
      if (mine === this.asked) {
        this.loading.set(false);
      }
    }
  }
}
