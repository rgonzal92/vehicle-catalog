import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Tab, TabList, TabPanel, TabPanels, Tabs } from 'primeng/tabs';
import { Tag } from 'primeng/tag';
import { Catalog, Catalogs, STATUS_NAMES } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Cell } from '../../shared/availability-matrix/matrix';
import { reasonOf } from '../../shared/reason-of';
import { HistoryTab } from './history-tab';
import { NotSent, SaveQueue, SaveStop } from './save-queue';

/**
 * A catalog as its owner works on it: what describes it, its matrix on the Features tab, and its
 * change history on the History tab. A working copy shows the library's current labels.
 *
 * The owner of a working copy in status Draft sets its cells, and each change is saved at once,
 * with no save button. Anyone else, and any other status, gets the matrix read-only.
 *
 * A change that is not saved goes back to what the cell was, marked with the reason. After a
 * revision conflict, or when no answer says whether a change was saved, the editor sends nothing
 * more and takes no further change until the catalog has been reloaded. A catalog that is no longer
 * in status Draft is reloaded at once, read-only.
 */
@Component({
  imports: [
    RouterLink,
    Button,
    Message,
    Tab,
    TabList,
    TabPanel,
    TabPanels,
    Tabs,
    Tag,
    AvailabilityMatrix,
    HistoryTab,
  ],
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

        @if (reloadNeeded(); as why) {
          <p-message class="mt-4 block" severity="error">
            <span>{{ why }}</span>
            <p-button
              class="ml-4"
              label="Reload"
              severity="secondary"
              size="small"
              (onClick)="reload()"
            />
          </p-message>
        }

        <p-tabs class="mt-6 block" [(value)]="tab">
          <p-tablist>
            <p-tab value="features">Features</p-tab>
            <p-tab value="history">History</p-tab>
          </p-tablist>
          <p-tabpanels>
            <p-tabpanel value="features">
              <app-availability-matrix
                #matrix
                class="h-[70vh] min-h-96"
                [contents]="catalog.snapshot"
                [categories]="fixedLists.categories()"
                [editable]="editable()"
                (cellChange)="save($event)"
              />
            </p-tabpanel>
            <p-tabpanel value="history">
              @if (historyShown()) {
                <app-history-tab [catalogId]="id" />
              }
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
  protected readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly catalog = signal<Catalog | null>(null);

  /** Whether the address names no catalog, or one this person may not open. */
  protected readonly missing = signal(false);

  protected readonly statusNames = STATUS_NAMES;

  /** The tab being shown. */
  protected readonly tab = signal<string | number | undefined>('features');

  /** The matrix, which is told how the save of each change it reported went. */
  private readonly matrix = viewChild<AvailabilityMatrix>('matrix');

  /**
   * Sends this page's edits in the order they were made, one at a time. Each time the catalog is
   * read, it gets a new one that starts from the revision read.
   */
  private readonly saves = signal<SaveQueue<Cell> | null>(null);

  /**
   * Whether the person may set cells: the catalog is theirs and in status Draft, and no failed save
   * has stopped the editor.
   */
  protected readonly editable = computed(() => {
    const catalog = this.catalog();
    return !!catalog?.owned && catalog.snapshot.status === 'DRAFT' && !this.saves()?.stopped();
  });

  /**
   * Whether the history is drawn: only while its tab is chosen, so that it is read afresh each
   * time, and only once every change made so far has had its outcome, so that none is missing.
   */
  protected readonly historyShown = computed(
    () => this.tab() === 'history' && (this.saves()?.idle() ?? true),
  );

  /** What to tell the person while nothing more is saved until they reload, or null otherwise. */
  protected readonly reloadNeeded = computed(() => {
    const stopped = this.saves()?.stopped();
    return stopped ? STOPPED[stopped] : null;
  });

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

  /**
   * Saves a cell the person just set, behind the saves still on their way, and tells the matrix how
   * it went. The editor never sends a change a second time.
   */
  protected async save(cell: Cell): Promise<void> {
    const saves = this.saves();
    if (!saves) {
      return;
    }

    try {
      await saves.add(cell);
      this.matrix()?.saved(cell);
    } catch (error) {
      if (error instanceof NotSent) {
        this.matrix()?.notSaved(cell, NOT_SENT);
        return;
      }

      const stopped = saves.stopped();
      const reason = reasonOf(error);
      this.matrix()?.notSaved(
        cell,
        stopped === 'uncertain' ? OUTCOME_UNKNOWN : `Not saved: ${reason}`,
      );
      if (!stopped) {
        this.messages.add({ severity: 'error', summary: 'Not saved', detail: reason });
      } else if (stopped === 'closed') {
        this.messages.add({ severity: 'warn', summary: 'Not saved', detail: reason });
        await this.open();
      }
    }
  }

  /** Reads the catalog again, which shows what was saved and lets editing go on. */
  protected reload(): void {
    void this.open();
  }

  private async open(): Promise<void> {
    try {
      const catalog = await this.catalogs.find(this.id);
      this.saves.set(
        new SaveQueue(
          (cell, revision) => this.catalogs.setCells(this.id, revision, [cell]),
          catalog.snapshot.revision,
        ),
      );
      this.catalog.set(catalog);
    } catch (error) {
      // The backend refuses an address that names no catalog the person may open. Any other failure
      // has been shown as a message.
      if (error instanceof HttpErrorResponse && [400, 404].includes(error.status)) {
        this.catalog.set(null);
        this.missing.set(true);
      }
    }
  }
}

/** What the banner says for each way a failed save stops the editor. */
const STOPPED: Record<SaveStop, string> = {
  conflict:
    'This catalog was changed somewhere else after you opened it, so your changes since were not saved. Reload the catalog to go on.',
  uncertain:
    'No answer says whether a change of yours was saved. Reload the catalog to see, and to go on.',
  closed: 'This catalog can no longer be edited. Reload it to see it as it is now.',
};

/** What the mark says on a cell whose change was never sent. */
const NOT_SENT = 'Not sent, because an earlier change was not saved.';

/** What the mark says on a cell whose change may or may not have been saved. */
const OUTCOME_UNKNOWN = 'No answer says whether this change was saved.';
