import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Button } from 'primeng/button';
import { TableModule } from 'primeng/table';
import { Catalog, Catalogs, VersionSummary } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { NewCatalogDialog } from '../../shared/new-catalog-dialog/new-catalog-dialog';

/**
 * A lineage's Approved versions: the list of them, and the read-only matrix of the one being shown,
 * which is the current one until another is chosen. Each version shows the labels it was approved
 * with.
 */
@Component({
  imports: [DatePipe, Button, TableModule, AvailabilityMatrix, NewCatalogDialog],
  selector: 'app-approved-page',
  template: `
    <div>
      @if (catalog(); as catalog) {
        <header>
          <h2 class="text-2xl font-semibold">{{ catalog.vehicleLine }} {{ catalog.modelYear }}</h2>
          <p class="mt-2" aria-live="polite" data-shown>
            Approved version {{ catalog.versionNumber }}, "{{ catalog.name }}", approved by
            {{ catalog.approvedBy }} on {{ catalog.approvedAt | date: 'mediumDate' }}.
          </p>
          <p-button
            class="mt-4 block"
            label="Create working copy"
            (onClick)="newCatalog.open(catalog)"
          />
        </header>
        <app-new-catalog-dialog #newCatalog />

        <section class="mt-8 max-w-5xl" aria-labelledby="versions">
          <h2 id="versions" class="text-xl font-semibold">Versions</h2>
          <p-table class="mt-2 block" size="small" [value]="versions()">
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
                <td class="text-right">
                  @if (version.catalogId === catalog.snapshot.catalogId) {
                    Shown below
                  } @else {
                    <p-button
                      label="Show"
                      severity="secondary"
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

        <section class="mt-8" aria-labelledby="matrix">
          <h2 id="matrix" class="text-xl font-semibold">Features</h2>
          <app-availability-matrix
            class="mt-2 h-[70vh] min-h-96"
            [contents]="catalog.snapshot"
            [categories]="fixedLists.categories()"
          />
        </section>
      } @else if (missing()) {
        <p>There is no Approved version at this address.</p>
      }
    </div>
  `,
})
export class ApprovedPage {
  private readonly catalogs = inject(Catalogs);
  protected readonly fixedLists = inject(FixedLists);
  private readonly lineageId = Number(inject(ActivatedRoute).snapshot.paramMap.get('lineageId'));

  /** The lineage's Approved versions, newest first. */
  protected readonly versions = signal<VersionSummary[]>([]);

  /** The version being shown, once it has loaded. */
  protected readonly catalog = signal<Catalog | null>(null);

  /** Whether the address names no lineage, or one without an Approved version. */
  protected readonly missing = signal(false);

  constructor() {
    void this.fixedLists.load();
    void this.open();
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
      }
    } catch {
      // The failure has already been shown as a message.
    }
  }

  /** Shows the current Approved version, which is the first of the list. */
  private async open(): Promise<void> {
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
  }
}
