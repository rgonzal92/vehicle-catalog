import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Copy } from '@primeicons/angular/copy';
import { Button, ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { TableModule } from 'primeng/table';
import { Catalog, CatalogChanges, Catalogs, VersionSummary } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Issue } from '../../shared/availability-matrix/matrix';
import { CatalogChangesList } from '../../shared/catalog-changes';
import { ExportDialog } from '../../shared/export-dialog';
import { IssueCounts, IssueList } from '../../shared/issues';
import { NewCatalogDialog } from '../../shared/new-catalog-dialog/new-catalog-dialog';
import { Loading, ReadFailed } from '../../shared/read-state';

/**
 * A lineage's Approved versions: the list of them, and the read-only matrix of the one being shown,
 * which is the current one until another is chosen. Each version shows the labels it was approved
 * with, and the issues it has against the library as it is today. Any other version can be compared
 * with the one shown.
 */
@Component({
  imports: [
    DatePipe,
    Copy,
    Button,
    ButtonDirective,
    ButtonIcon,
    ButtonLabel,
    TableModule,
    AvailabilityMatrix,
    CatalogChangesList,
    IssueCounts,
    IssueList,
    NewCatalogDialog,
    ExportDialog,
    Loading,
    ReadFailed,
  ],
  selector: 'app-approved-page',
  template: `
    <div class="grid gap-6">
      @if (catalog(); as catalog) {
        <header class="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h2 class="text-2xl font-semibold">
              {{ catalog.vehicleLine }} {{ catalog.modelYear }}
            </h2>
            <p class="mt-1 text-muted-color" aria-live="polite" data-shown>
              Approved version {{ catalog.versionNumber }}, "{{ catalog.name }}", approved by
              {{ catalog.approvedBy }} on {{ catalog.approvedAt | date: 'mediumDate' }}.
            </p>
            <app-new-catalog-dialog #newCatalog />
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <p-button label="Export" severity="secondary" (onClick)="exporting.start()" />
            <app-export-dialog #exporting [catalogId]="catalog.snapshot.catalogId" />
            <button pButton type="button" (click)="newCatalog.open(catalog)">
              <svg data-p-icon="copy" pButtonIcon />
              <span pButtonLabel>Create working copy</span>
            </button>
          </div>
        </header>

        <section class="surface max-w-5xl" aria-labelledby="versions">
          <div class="surface-header">
            <h2 id="versions" class="font-semibold">Versions</h2>
          </div>
          <p-table [value]="versions()">
            <ng-template #header>
              <tr>
                <th scope="col">Version</th>
                <th scope="col">Name</th>
                <th scope="col">Approved by</th>
                <th scope="col">Approved</th>
                <th scope="col"><span class="sr-only">Shown</span></th>
              </tr>
            </ng-template>
            <ng-template #body let-version>
              <tr>
                <td>{{ version.versionNumber }}</td>
                <td>{{ version.name }}</td>
                <td>{{ version.approvedBy }}</td>
                <td>{{ version.approvedAt | date: 'mediumDate' }}</td>
                <td class="text-right whitespace-nowrap">
                  @if (version.catalogId === catalog.snapshot.catalogId) {
                    Shown below
                  } @else {
                    <p-button
                      label="Compare"
                      severity="secondary"
                      size="small"
                      [text]="true"
                      [ariaLabel]="
                        'Compare version ' +
                        version.versionNumber +
                        ' with version ' +
                        catalog.versionNumber
                      "
                      (onClick)="compare(version, catalog)"
                    />
                    <p-button
                      label="Show"
                      severity="secondary"
                      size="small"
                      [text]="true"
                      [ariaLabel]="'Show version ' + version.versionNumber"
                      (onClick)="show(version)"
                    />
                  }
                </td>
              </tr>
            </ng-template>
          </p-table>
        </section>

        @if (comparison(); as compared) {
          <section class="surface max-w-5xl" aria-labelledby="comparison">
            <div class="surface-header">
              <h2 id="comparison" class="font-semibold" tabindex="-1">
                Changes from version {{ compared.from }} to version {{ compared.to }}
              </h2>
              <p-button
                label="Close"
                severity="secondary"
                size="small"
                [text]="true"
                ariaLabel="Close the comparison"
                (onClick)="comparison.set(null)"
              />
            </div>
            <app-catalog-changes
              class="py-4"
              name="comparison"
              none="Nothing differs between the two versions."
              [changes]="compared.changes"
            />
          </section>
        }

        <section class="surface max-w-5xl" aria-labelledby="issues">
          <div class="surface-header">
            <h2 id="issues" class="font-semibold">Issues</h2>
            <app-issue-counts
              aria-live="polite"
              data-issue-counts
              [errors]="errors()"
              [warnings]="issues().length - errors()"
            />
          </div>
          <app-issue-list
            none="This version has no issues, as the library is today."
            [issues]="issues()"
            [contents]="catalog.snapshot"
            (showCell)="showCell($event, matrix)"
          />
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
          />
        </section>
      } @else if (missing()) {
        <p>There is no Approved version at this address.</p>
      } @else if (failed()) {
        <app-read-failed what="The Approved catalog could not be read." (again)="open()" />
      } @else {
        <app-loading class="surface" />
      }
    </div>
  `,
})
export class ApprovedPage {
  private readonly catalogs = inject(Catalogs);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  protected readonly fixedLists = inject(FixedLists);
  private readonly lineageId = Number(inject(ActivatedRoute).snapshot.paramMap.get('lineageId'));

  /** The lineage's Approved versions, newest first. */
  protected readonly versions = signal<VersionSummary[]>([]);

  /** The version being shown, once it has loaded. */
  protected readonly catalog = signal<Catalog | null>(null);

  /**
   * What validation finds in the version being shown against the library as it is today, which may
   * be more than when it was approved. The version itself does not change.
   */
  protected readonly issues = computed(() => {
    const shown = this.catalog();
    return shown ? this.catalogs.issuesOf(shown.snapshot.catalogId) : [];
  });
  protected readonly errors = computed(
    () => this.issues().filter(({ severity }) => severity === 'ERROR').length,
  );

  /** Whether the address names no lineage, or one without an Approved version. */
  protected readonly missing = signal(false);

  /** Whether the page could not be read, which it then says. */
  protected readonly failed = signal(false);

  constructor() {
    void this.fixedLists.load();
    void this.open();
  }

  /**
   * The comparison being shown: the numbers of the two versions, the earlier one first, and what
   * changed from it to the later one. Null while no two versions are compared.
   */
  protected readonly comparison = signal<{
    from: number;
    to: number;
    changes: CatalogChanges;
  } | null>(null);

  /**
   * Compares a version with the one being shown, from the earlier of the two to the later, and
   * puts the focus on what it found.
   */
  protected async compare(version: VersionSummary, shown: Catalog): Promise<void> {
    const other = { catalogId: shown.snapshot.catalogId, versionNumber: shown.versionNumber ?? 0 };
    const [earlier, later] =
      version.versionNumber < other.versionNumber ? [version, other] : [other, version];
    try {
      const changes = await this.catalogs.diff(later.catalogId, earlier.catalogId);
      this.comparison.set({ from: earlier.versionNumber, to: later.versionNumber, changes });
      afterNextRender(() => this.host.querySelector<HTMLElement>('#comparison')?.focus(), {
        injector: this.injector,
      });
    } catch {
      // The failure has already been shown as a message.
    }
  }

  /** How many versions have been asked for, so that a slow answer to an earlier choice is dropped. */
  private asked = 0;

  /** Shows the version, or leaves the page as it is when the version cannot be read. */
  protected async show(version: VersionSummary): Promise<void> {
    const mine = ++this.asked;
    try {
      const catalog = await this.catalogs.find(version.catalogId);
      if (mine === this.asked) {
        this.catalog.set(catalog);
        // A comparison is with the version shown, so it goes when another is.
        this.comparison.set(null);
      }
    } catch {
      // The failure has already been shown as a message.
    }
  }

  /** Brings the cell an issue is about into view in the matrix. */
  protected showCell(issue: Issue, matrix: AvailabilityMatrix): void {
    const { featureId, trimId, regionCode } = issue;
    if (featureId !== null && trimId !== null && regionCode !== null) {
      matrix.show({ featureId, trimId, regionCode });
    }
  }

  /** Shows the current Approved version, which is the first of the list. */
  protected async open(): Promise<void> {
    this.failed.set(false);
    try {
      const versions = await this.catalogs.versions(this.lineageId);
      this.versions.set(versions);
      this.missing.set(versions.length === 0);
      if (versions.length > 0) {
        await this.show(versions[0]);
      }
    } catch (error) {
      // The backend refuses an address that names no lineage. Any other failure has been shown as
      // a message.
      this.missing.set(error instanceof HttpErrorResponse && [400, 404].includes(error.status));
    }
    // A version that could not be read leaves nothing to show.
    this.failed.set(!this.missing() && !this.catalog());
  }
}
