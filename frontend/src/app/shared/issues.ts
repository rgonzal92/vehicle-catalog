import { Component, computed, input, output } from '@angular/core';
import { Button } from 'primeng/button';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Issue, MatrixContents } from './availability-matrix/matrix';

/** How many Errors and Warnings a catalog has, as tags, or that it has no issues. */
@Component({
  imports: [Tag],
  selector: 'app-issue-counts',
  host: { class: 'flex flex-wrap items-center gap-2' },
  template: `
    @if (errors() > 0) {
      <p-tag severity="danger" [value]="errors() + (errors() === 1 ? ' Error' : ' Errors')" />
    }
    @if (warnings() > 0) {
      <p-tag severity="warn" [value]="warnings() + (warnings() === 1 ? ' Warning' : ' Warnings')" />
    }
    @if (errors() + warnings() === 0) {
      <p-tag severity="success" value="No issues" />
    }
  `,
})
export class IssueCounts {
  readonly errors = input.required<number>();
  readonly warnings = input.required<number>();
}

/**
 * A catalog's issues, Errors before Warnings: what each finds, what it is about by the names the
 * catalog shows, and whether it comes from a rule. An issue about a cell offers to show the cell.
 */
@Component({
  imports: [Button, TableModule, Tag],
  selector: 'app-issue-list',
  template: `
    @if (issues().length > 0) {
      <p-table size="small" [value]="issues()">
        <ng-template #header>
          <tr>
            <th scope="col">Severity</th>
            <th scope="col">Issue</th>
            <th scope="col">About</th>
            <th scope="col">From</th>
            <th scope="col"><span class="sr-only">Actions</span></th>
          </tr>
        </ng-template>
        <ng-template #body let-issue>
          <tr>
            <td>
              <p-tag
                [severity]="issue.severity === 'ERROR' ? 'danger' : 'warn'"
                [value]="issue.severity === 'ERROR' ? 'Error' : 'Warning'"
              />
            </td>
            <td>{{ issue.message }}</td>
            <td>{{ about(issue) }}</td>
            <td>{{ from(issue) }}</td>
            <td class="text-right whitespace-nowrap">
              @if (isAboutACell(issue)) {
                <p-button
                  label="Show cell"
                  severity="secondary"
                  size="small"
                  [text]="true"
                  [ariaLabel]="'Show the cell of this issue: ' + issue.message"
                  (onClick)="showCell.emit(issue)"
                />
              }
              @if (showsRules() && issue.rule?.origin === 'CATALOG') {
                <p-button
                  label="Show rule"
                  severity="secondary"
                  size="small"
                  [text]="true"
                  [ariaLabel]="'Show the rule of this issue: ' + issue.message"
                  (onClick)="showRule.emit(issue)"
                />
              }
            </td>
          </tr>
        </ng-template>
      </p-table>
    } @else {
      <p class="surface-empty">{{ none() }}</p>
    }
  `,
})
export class IssueList {
  readonly issues = input.required<Issue[]>();

  /** The catalog the issues are about, whose names say what each of them is about. */
  readonly contents = input.required<MatrixContents>();

  /** What the list says when there is no issue. */
  readonly none = input('This catalog has no issues.');

  /** Whether an issue that comes from a rule of the catalog offers to show that rule. */
  readonly showsRules = input(false);

  /** An issue about a cell, which the person asked to see the cell of. */
  readonly showCell = output<Issue>();

  /** An issue from a rule of the catalog, which the person asked to see the rule of. */
  readonly showRule = output<Issue>();

  private readonly names = computed(() => {
    const { featureRows, trims, regions } = this.contents();
    return {
      features: new Map(featureRows.map(({ id, name }) => [id, name])),
      trims: new Map(trims.map(({ id, name }) => [id, name])),
      regions: new Map(regions.map(({ code, name }) => [code, name])),
    };
  });

  protected isAboutACell(issue: Issue): boolean {
    return issue.featureId !== null && issue.trimId !== null && issue.regionCode !== null;
  }

  /** Where an issue comes from: a rule of the library, a rule of the catalog, or no rule. */
  protected from(issue: Issue): string {
    return issue.rule ? (issue.rule.origin === 'GLOBAL' ? 'Global rule' : 'Catalog rule') : '';
  }

  /** What an issue is about, by the names the catalog has for it: a cell, an offering, or less. */
  protected about(issue: Issue): string {
    const { features, trims, regions } = this.names();
    const feature = issue.featureId === null ? undefined : features.get(issue.featureId);
    const trim = issue.trimId === null ? undefined : trims.get(issue.trimId);
    const region = issue.regionCode === null ? undefined : regions.get(issue.regionCode);
    const offering = trim && region ? `${trim} in ${region}` : (trim ?? region);

    return [feature, offering].filter(Boolean).join(', ') || 'The catalog';
  }
}
