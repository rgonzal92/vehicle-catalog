import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Tab, TabList, TabPanel, TabPanels, Tabs } from 'primeng/tabs';
import { Tag } from 'primeng/tag';
import { Catalog, Catalogs, STATUS_NAMES } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Cell } from '../../shared/availability-matrix/matrix';
import { reasonOf } from '../../shared/reason-of';
import { SaveQueue } from './save-queue';

/**
 * A catalog as its owner works on it: what describes it, and its matrix on the Features tab. A
 * working copy shows the library's current labels.
 *
 * The owner of a working copy in status Draft sets its cells, and each change is saved at once,
 * with no save button. Anyone else, and any other status, gets the matrix read-only.
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
                [editable]="editable()"
                (cellChange)="save($event)"
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
  private readonly messages = inject(MessageService);
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly catalog = signal<Catalog | null>(null);

  /** Whether the address names no catalog, or one this person may not open. */
  protected readonly missing = signal(false);

  protected readonly statusNames = STATUS_NAMES;

  /** Whether the person may set cells: the catalog is theirs and in status Draft. */
  protected readonly editable = computed(() => {
    const catalog = this.catalog();
    return !!catalog?.owned && catalog.snapshot.status === 'DRAFT';
  });

  /** Sends this tab's edits in the order they were made, one at a time. */
  private saves?: SaveQueue<Cell>;

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

  /** Saves a cell the person just set, behind the saves still on their way. */
  protected async save(cell: Cell): Promise<void> {
    try {
      await this.saves?.add(cell);
    } catch (error) {
      this.messages.add({ severity: 'error', summary: 'Not saved', detail: reasonOf(error) });
    }
  }

  private async open(): Promise<void> {
    try {
      const catalog = await this.catalogs.find(this.id);
      this.saves = new SaveQueue(
        (cell, revision) => this.catalogs.setCells(this.id, revision, [cell]),
        catalog.snapshot.revision,
      );
      this.catalog.set(catalog);
    } catch (error) {
      // The backend refuses an address that names no catalog the person may open. Any other failure
      // has been shown as a message.
      this.missing.set(error instanceof HttpErrorResponse && [400, 404].includes(error.status));
    }
  }
}
