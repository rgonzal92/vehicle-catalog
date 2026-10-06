import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Tab, TabList, TabPanel, TabPanels, Tabs } from 'primeng/tabs';
import { Tag } from 'primeng/tag';
import { Catalog, Catalogs, STATUS_NAMES } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';

/**
 * A catalog as its owner works on it: what describes it, and its matrix on the Features tab. A
 * working copy shows the library's current labels.
 */
@Component({
  imports: [RouterLink, Tab, TabList, TabPanel, TabPanels, Tabs, Tag, AvailabilityMatrix],
  selector: 'app-catalog-editor-page',
  template: `
    <main class="px-6 py-10">
      <a class="text-primary underline" routerLink="/dashboard">Dashboard</a>
      @if (catalog(); as catalog) {
        <header class="mt-4">
          <h1 class="text-2xl font-semibold">{{ catalog.name }}</h1>
          <dl class="mt-2 flex flex-wrap gap-x-8 gap-y-2">
            <div>
              <dt class="text-sm text-muted-color">Vehicle line</dt>
              <dd>{{ catalog.vehicleLine }}</dd>
            </div>
            <div>
              <dt class="text-sm text-muted-color">Model year</dt>
              <dd>{{ catalog.modelYear }}</dd>
            </div>
            <div>
              <dt class="text-sm text-muted-color">Status</dt>
              <dd><p-tag severity="secondary" [value]="statusNames[catalog.snapshot.status]" /></dd>
            </div>
            <div>
              <dt class="text-sm text-muted-color">Base</dt>
              <dd data-base>{{ baseInWords(catalog) }}</dd>
            </div>
          </dl>
        </header>

        <p-tabs class="mt-6 block" value="features">
          <p-tablist>
            <p-tab value="features">Features</p-tab>
          </p-tablist>
          <p-tabpanels>
            <p-tabpanel value="features">
              <app-availability-matrix
                class="h-[70vh] min-h-96"
                [contents]="catalog.snapshot"
                [categories]="fixedLists.categories()"
              />
            </p-tabpanel>
          </p-tabpanels>
        </p-tabs>
      } @else if (missing()) {
        <h1 class="mt-4 text-2xl font-semibold">Catalog</h1>
        <p class="mt-2">There is no catalog at this address.</p>
      }
    </main>
  `,
})
export class CatalogEditorPage {
  private readonly catalogs = inject(Catalogs);
  protected readonly fixedLists = inject(FixedLists);
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly catalog = signal<Catalog | null>(null);

  /** Whether the address names no catalog, or one this person may not open. */
  protected readonly missing = signal(false);

  protected readonly statusNames = STATUS_NAMES;

  constructor() {
    void this.fixedLists.load();
    void this.open();
  }

  /** The base as the header names it. A base of an earlier model year is a carryover. */
  protected baseInWords({ base, modelYear }: Catalog): string {
    if (!base) {
      return 'None (started empty)';
    }
    return base.modelYear === modelYear
      ? `Approved v${base.versionNumber}`
      : `${base.modelYear} Approved v${base.versionNumber} (carryover)`;
  }

  private async open(): Promise<void> {
    try {
      this.catalog.set(await this.catalogs.find(this.id));
    } catch (error) {
      // The backend refuses an address that names no catalog the person may open. Any other failure
      // has been shown as a message.
      this.missing.set(error instanceof HttpErrorResponse && [400, 404].includes(error.status));
    }
  }
}
