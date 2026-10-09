import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { baseInWords, Catalog, CatalogChanges, Catalogs } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Issue, MatrixChanges } from '../../shared/availability-matrix/matrix';
import { CatalogChangesList } from '../../shared/catalog-changes';
import { IssueCounts, IssueList } from '../../shared/issues';
import { Loading, ReadFailed } from '../../shared/read-state';

/**
 * A Submitted catalog as a reviewer decides on it: whose it is and what its owner said, what it
 * changes against its base, its matrix with those changes marked, and its issues. A catalog made by
 * carryover shows what changed since the earlier model year's version, and one that started empty
 * shows everything as added.
 */
@Component({
  imports: [
    DatePipe,
    RouterLink,
    AvailabilityMatrix,
    CatalogChangesList,
    IssueCounts,
    IssueList,
    Loading,
    ReadFailed,
  ],
  selector: 'app-review-page',
  template: `
    <div class="grid gap-6">
      @if (catalog(); as catalog) {
        <header class="grid gap-3">
          <h2 class="text-2xl font-semibold">{{ catalog.name }}</h2>
          <dl class="flex flex-wrap gap-x-8 gap-y-2">
            <div>
              <dt class="text-sm text-muted-color">Vehicle line</dt>
              <dd>{{ catalog.vehicleLine }}</dd>
            </div>
            <div>
              <dt class="text-sm text-muted-color">Model year</dt>
              <dd>{{ catalog.modelYear }}</dd>
            </div>
            <div>
              <dt class="text-sm text-muted-color">Owner</dt>
              <dd>{{ catalog.owner }}</dd>
            </div>
            <div>
              <dt class="text-sm text-muted-color">Base</dt>
              <dd data-base>{{ baseInWords(catalog) }}</dd>
            </div>
            @if (catalog.snapshot.status === 'SUBMITTED') {
              <div>
                <dt class="text-sm text-muted-color">Submitted</dt>
                <dd>{{ catalog.submittedAt | date: 'medium' }}</dd>
              </div>
              @if (catalog.submitNote) {
                <div class="basis-full">
                  <dt class="text-sm text-muted-color">Note for the reviewer</dt>
                  <dd class="whitespace-pre-line" data-submit-note>{{ catalog.submitNote }}</dd>
                </div>
              }
            }
          </dl>
        </header>

        @if (catalog.snapshot.status !== 'SUBMITTED') {
          <p class="notice" role="status" data-notice="nothing-to-review">
            This catalog is not waiting for review.
            <a
              class="font-medium text-primary hover:underline"
              [routerLink]="
                catalog.snapshot.status === 'APPROVED'
                  ? ['/approved', catalog.snapshot.lineageId]
                  : ['/catalogs', catalog.snapshot.catalogId]
              "
            >
              Open the catalog
            </a>
          </p>
        } @else {
          <section class="surface max-w-5xl" aria-labelledby="changes">
            <div class="surface-header">
              <h2 id="changes" class="font-semibold">What it changes</h2>
              <p class="text-sm text-muted-color">Against its base: {{ baseInWords(catalog) }}</p>
            </div>
            @if (changes(); as changed) {
              <app-catalog-changes
                class="py-4"
                name="review"
                none="This catalog changes nothing against its base."
                [changes]="changed"
              />
            }
          </section>

          <section class="surface" aria-labelledby="matrix">
            <div class="surface-header">
              <h2 id="matrix" class="font-semibold">Features</h2>
            </div>
            <app-availability-matrix
              #matrix
              class="h-[70vh] min-h-96"
              [contents]="catalog.snapshot"
              [categories]="fixedLists.categories()"
              [issues]="issues()"
              [changes]="marks()"
            />
          </section>

          <section class="surface max-w-5xl" aria-labelledby="issues">
            <div class="surface-header">
              <h2 id="issues" class="font-semibold">Issues</h2>
              <app-issue-counts
                data-issue-counts
                [errors]="errors()"
                [warnings]="issues().length - errors()"
              />
            </div>
            <app-issue-list
              [issues]="issues()"
              [contents]="catalog.snapshot"
              (showCell)="showCell($event, matrix)"
            />
          </section>
        }
      } @else if (missing()) {
        <p>There is no catalog to review at this address.</p>
      } @else if (failed()) {
        <app-read-failed what="The catalog could not be read." (again)="open()" />
      } @else {
        <app-loading class="surface" />
      }
    </div>
  `,
})
export class ReviewPage {
  private readonly catalogs = inject(Catalogs);
  protected readonly fixedLists = inject(FixedLists);
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  /** The catalog under review, once it has loaded. */
  protected readonly catalog = signal<Catalog | null>(null);

  /** What the catalog changes against its base, once that has loaded. */
  protected readonly changes = signal<CatalogChanges | null>(null);

  /** Whether the address names no catalog, or one this person may not open. */
  protected readonly missing = signal(false);

  /** Whether the page could not be read, which it then says. */
  protected readonly failed = signal(false);

  protected readonly baseInWords = baseInWords;

  protected readonly issues = computed(() => this.catalogs.issuesOf(this.id));
  protected readonly errors = computed(
    () => this.issues().filter(({ severity }) => severity === 'ERROR').length,
  );

  /** The changes as the matrix marks them: what each changed cell was, and what was added. */
  protected readonly marks = computed<MatrixChanges | null>(() => {
    const changes = this.changes();
    return (
      changes && {
        before: new Map(
          changes.cellsChanged.map((cell) => [
            `${cell.featureId}:${cell.trimId}:${cell.regionCode}`,
            cell.before,
          ]),
        ),
        addedFeatures: new Set(changes.featureRowsAdded.map(({ id }) => id)),
        addedOfferings: new Set(
          changes.offeringsAdded.map(({ trimId, regionCode }) => `${trimId}:${regionCode}`),
        ),
      }
    );
  });

  constructor() {
    void this.fixedLists.load();
    void this.open();
  }

  /** Brings the cell an issue is about into view in the matrix. */
  protected showCell(issue: Issue, matrix: AvailabilityMatrix): void {
    const { featureId, trimId, regionCode } = issue;
    if (featureId !== null && trimId !== null && regionCode !== null) {
      matrix.show({ featureId, trimId, regionCode });
    }
  }

  /** Reads the catalog and what it changes against its base. */
  protected async open(): Promise<void> {
    this.failed.set(false);
    try {
      const [catalog, changes] = await Promise.all([
        this.catalogs.find(this.id),
        this.catalogs.diff(this.id, 'base'),
      ]);
      this.catalog.set(catalog);
      this.changes.set(changes);
    } catch (error) {
      // The backend refuses an address that names no catalog the person may open. Any other failure
      // has been shown as a message.
      this.missing.set(error instanceof HttpErrorResponse && [400, 404].includes(error.status));
      this.failed.set(!this.missing());
    }
  }
}
