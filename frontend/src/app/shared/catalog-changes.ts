import { Component, computed, input } from '@angular/core';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { CatalogChanges } from '../core/catalogs';
import { AVAILABILITY_NAMES } from './availability-matrix/matrix';

/** One thing a catalog has more or less of than another: a trim, a region, and so on. */
interface ContentChange {
  change: 'Added' | 'Removed';
  kind: string;
  what: string;
}

/** One rule that a catalog has more or less of than another, or that says something else. */
interface RuleChange {
  change: 'Added' | 'Removed' | 'Changed';
  rule: string;
  /** What a changed rule said before. */
  before: string | null;
}

/**
 * What changed from one catalog to another, grouped by kind: what the catalog has more or less of,
 * the cells whose availability changed, and the rules. Every change says in words what it is, and
 * is never told by color alone.
 */
@Component({
  imports: [TableModule, Tag],
  selector: 'app-catalog-changes',
  host: { class: 'grid gap-6' },
  template: `
    @if (contents().length + changes().cellsChanged.length + rules().length === 0) {
      <p class="surface-empty">{{ none() }}</p>
    }
    @if (contents().length > 0) {
      <section [attr.aria-labelledby]="name() + '-contents'">
        <h3 class="px-4 pb-2 font-semibold" [id]="name() + '-contents'">
          Trims, regions, offerings, and feature rows
        </h3>
        <p-table size="small" [value]="contents()">
          <ng-template #header>
            <tr>
              <th scope="col">Change</th>
              <th scope="col">Of</th>
              <th scope="col">What</th>
            </tr>
          </ng-template>
          <ng-template #body let-row>
            <tr>
              <td>
                <p-tag
                  [severity]="row.change === 'Added' ? 'success' : 'secondary'"
                  [value]="row.change"
                />
              </td>
              <td>{{ row.kind }}</td>
              <td>{{ row.what }}</td>
            </tr>
          </ng-template>
        </p-table>
      </section>
    }
    @if (changes().cellsChanged.length > 0) {
      <section [attr.aria-labelledby]="name() + '-cells'">
        <h3 class="px-4 pb-2 font-semibold" [id]="name() + '-cells'">Cells</h3>
        <p-table
          size="small"
          [value]="changes().cellsChanged"
          [paginator]="changes().cellsChanged.length > pageSize"
          [rows]="pageSize"
        >
          <ng-template #header>
            <tr>
              <th scope="col">Feature</th>
              <th scope="col">Offering</th>
              <th scope="col">Before</th>
              <th scope="col">After</th>
            </tr>
          </ng-template>
          <ng-template #body let-cell>
            <tr>
              <td>{{ cell.feature }} ({{ cell.featureCode }})</td>
              <td>{{ cell.trim }} in {{ cell.region }}</td>
              <td>{{ availabilityNames[cell.before] }}</td>
              <td>{{ availabilityNames[cell.after] }}</td>
            </tr>
          </ng-template>
        </p-table>
      </section>
    }
    @if (rules().length > 0) {
      <section [attr.aria-labelledby]="name() + '-rules'">
        <h3 class="px-4 pb-2 font-semibold" [id]="name() + '-rules'">Rules</h3>
        <p-table size="small" [value]="rules()">
          <ng-template #header>
            <tr>
              <th scope="col">Change</th>
              <th scope="col">Rule</th>
            </tr>
          </ng-template>
          <ng-template #body let-row>
            <tr>
              <td>
                <p-tag
                  [severity]="
                    row.change === 'Added'
                      ? 'success'
                      : row.change === 'Changed'
                        ? 'info'
                        : 'secondary'
                  "
                  [value]="row.change"
                />
              </td>
              <td>
                {{ row.rule }}
                @if (row.before) {
                  <span class="block text-sm text-muted-color">Before: {{ row.before }}</span>
                }
              </td>
            </tr>
          </ng-template>
        </p-table>
      </section>
    }
  `,
})
export class CatalogChangesList {
  readonly changes = input.required<CatalogChanges>();

  /** What tells this list's headings from another's on the same page. */
  readonly name = input('changes');

  /** What the list says when nothing changed. */
  readonly none = input('Nothing differs.');

  protected readonly availabilityNames: Record<string, string> = AVAILABILITY_NAMES;
  protected readonly pageSize = 25;

  protected readonly contents = computed<ContentChange[]>(() => {
    const changes = this.changes();
    const rows = (
      change: ContentChange['change'],
      kind: string,
      whats: string[],
    ): ContentChange[] => whats.map((what) => ({ change, kind, what }));
    const offering = ({ trim, region }: { trim: string; region: string }) => `${trim} in ${region}`;
    const feature = ({ name, code }: { name: string; code: string }) => `${name} (${code})`;

    return [
      ...rows(
        'Added',
        'Trim',
        changes.trimsAdded.map(({ name }) => name),
      ),
      ...rows(
        'Removed',
        'Trim',
        changes.trimsRemoved.map(({ name }) => name),
      ),
      ...rows(
        'Added',
        'Region',
        changes.regionsAdded.map(({ name }) => name),
      ),
      ...rows(
        'Removed',
        'Region',
        changes.regionsRemoved.map(({ name }) => name),
      ),
      ...rows('Added', 'Offering', changes.offeringsAdded.map(offering)),
      ...rows('Removed', 'Offering', changes.offeringsRemoved.map(offering)),
      ...rows('Added', 'Feature row', changes.featureRowsAdded.map(feature)),
      ...rows('Removed', 'Feature row', changes.featureRowsRemoved.map(feature)),
    ];
  });

  protected readonly rules = computed<RuleChange[]>(() => {
    const { rulesAdded, rulesRemoved, rulesChanged } = this.changes();

    return [
      ...rulesAdded.map(({ rule }) => ({ change: 'Added' as const, rule, before: null })),
      ...rulesRemoved.map(({ rule }) => ({ change: 'Removed' as const, rule, before: null })),
      ...rulesChanged.map(({ before, after }) => ({
        change: 'Changed' as const,
        rule: after,
        before,
      })),
    ];
  });
}
