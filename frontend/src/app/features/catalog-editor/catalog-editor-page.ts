import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Tab, TabList, TabPanel, TabPanels, Tabs } from 'primeng/tabs';
import { Tag } from 'primeng/tag';
import { Catalog, CatalogEdit, Catalogs, STATUS_NAMES } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Cell } from '../../shared/availability-matrix/matrix';
import { reasonOf } from '../../shared/reason-of';
import { HistoryTab } from './history-tab';
import { ManageOfferingsDialog } from './manage-offerings-dialog';
import { NotSent, SaveQueue, SaveStop } from './save-queue';

/**
 * A catalog as its owner works on it: what describes it, its matrix on the Features tab, and its
 * change history on the History tab. A working copy shows the library's current labels.
 *
 * The owner of a working copy in status Draft sets its cells and manages its trims, regions, and
 * offerings, and each change is saved at once, with no save button. Anyone else, and any other
 * status, gets the matrix read-only. Whole regions can be hidden to keep the matrix narrow.
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
    ManageOfferingsDialog,
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
              <div class="mb-2 flex flex-wrap items-center justify-between gap-4">
                <div>
                  @if (catalog.snapshot.regions.length > 0) {
                    <fieldset class="flex flex-wrap items-center gap-4">
                      <legend class="float-left mr-4 text-sm text-muted-color">
                        Regions shown
                      </legend>
                      @for (region of catalog.snapshot.regions; track region.code) {
                        <label class="flex items-center gap-1">
                          <input
                            type="checkbox"
                            class="size-4"
                            [checked]="!hiddenRegions().has(region.code)"
                            (change)="showRegion(region.code, $any($event.target).checked)"
                          />
                          {{ region.name }}
                        </label>
                      }
                    </fieldset>
                  }
                </div>
                @if (editable() || managing()) {
                  <p-button
                    label="Manage trims and regions"
                    severity="secondary"
                    [disabled]="managing()"
                    (onClick)="manage(offerings)"
                  />
                }
              </div>
              <app-availability-matrix
                #matrix
                class="h-[70vh] min-h-96"
                [contents]="catalog.snapshot"
                [categories]="fixedLists.categories()"
                [editable]="editable()"
                [hiddenRegions]="hiddenRegions()"
                (cellChange)="save($event)"
              />
              <app-manage-offerings-dialog
                #offerings
                [catalog]="catalog"
                [run]="restructure"
                [editable]="editable()"
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
  private readonly saves = signal<SaveQueue<CatalogEdit> | null>(null);

  /**
   * The codes of the regions the person has hidden from the matrix. It is a way of looking at the
   * catalog and changes nothing in it.
   */
  protected readonly hiddenRegions = signal<ReadonlySet<string>>(new Set());

  /**
   * Whether the catalog is being read again before the dialog for its trims and regions opens.
   * Nothing is edited meanwhile, so that no change is made from the revision being left behind.
   */
  protected readonly managing = signal(false);

  /**
   * Whether the catalog could not be read again after a change of its trims, regions, or offerings
   * was saved. What the page shows is then behind what is saved.
   */
  private readonly behind = signal(false);

  /**
   * Whether the person may edit the catalog: it is theirs and in status Draft, and the page shows
   * it as saved, with no failed save or read in the way.
   */
  protected readonly editable = computed(() => {
    const catalog = this.catalog();
    return (
      !!catalog?.owned &&
      catalog.snapshot.status === 'DRAFT' &&
      !this.saves()?.stopped() &&
      !this.managing() &&
      !this.behind()
    );
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
    return stopped ? STOPPED[stopped] : this.behind() ? BEHIND : null;
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
      const changed = await saves.add((revision) =>
        this.catalogs.setCells(this.id, revision, [cell]),
      );
      this.matrix()?.saved(cell);
      if (!changed) {
        this.noNewChanges();
      }
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
        await this.closed(reason);
      }
    }
  }

  /**
   * Sends a change of the catalog's trims, regions, or offerings behind the saves on their way, and
   * reads the catalog again once it is saved, since such a change alters what the matrix is made
   * of. It fails with the backend's refusal when the change is not saved.
   */
  protected readonly restructure = async (edit: CatalogEdit): Promise<void> => {
    const saves = this.saves();
    if (!saves) {
      return;
    }

    try {
      if (!(await saves.add(edit))) {
        this.noNewChanges();
      }
    } catch (error) {
      if (saves.stopped() === 'closed') {
        await this.closed(reasonOf(error));
      }
      throw error;
    }
    if (!(await this.open())) {
      this.behind.set(true);
    }
  };

  /**
   * Opens the dialog for the catalog's trims, regions, and offerings. It says how many cells go
   * with a removal, so the catalog is first read again as saved, with the cells set since. The
   * dialog stays shut when that fails.
   */
  protected async manage(dialog: ManageOfferingsDialog): Promise<void> {
    const saves = this.saves();
    this.managing.set(true);
    try {
      await saves?.whenIdle();
      if (saves?.stopped() || !(await this.open())) {
        return;
      }
    } finally {
      this.managing.set(false);
    }
    await dialog.open();
  }

  /** Shows or hides a region's offerings in the matrix. */
  protected showRegion(code: string, shown: boolean): void {
    this.hiddenRegions.update((hidden) => {
      const next = new Set(hidden);
      if (shown) {
        next.delete(code);
      } else {
        next.add(code);
      }
      return next;
    });
  }

  /** Tells the person that what they asked for was already so, and that nothing was changed. */
  private noNewChanges(): void {
    this.messages.add({
      severity: 'info',
      summary: 'No new changes',
      detail: 'The catalog was already as you set it, so nothing was saved.',
    });
  }

  /** Says why the catalog can no longer be edited, and reads it again as it is now. */
  private async closed(reason: string): Promise<void> {
    this.messages.add({ severity: 'warn', summary: 'Not saved', detail: reason });
    await this.open();
  }

  /** Reads the catalog again, which shows what was saved and lets editing go on. */
  protected reload(): void {
    void this.open();
  }

  /**
   * Reads the catalog and starts the queue of its edits over from the revision read. It says
   * whether the catalog was read.
   */
  private async open(): Promise<boolean> {
    try {
      const catalog = await this.catalogs.find(this.id);
      const regions = new Set(catalog.snapshot.regions.map(({ code }) => code));
      this.saves.set(
        new SaveQueue<CatalogEdit>((edit, revision) => edit(revision), catalog.snapshot.revision),
      );
      this.catalog.set(catalog);
      this.behind.set(false);
      // A region the catalog no longer has is no longer hidden, should it be added again.
      this.hiddenRegions.update(
        (hidden) => new Set([...hidden].filter((code) => regions.has(code))),
      );
      return true;
    } catch (error) {
      // The backend refuses an address that names no catalog the person may open. Any other failure
      // has been shown as a message.
      if (error instanceof HttpErrorResponse && [400, 404].includes(error.status)) {
        this.catalog.set(null);
        this.missing.set(true);
      }
      return false;
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

/** What the banner says when the page could not catch up with a change it saved. */
const BEHIND =
  'Your change was saved, but the catalog could not be read again. Reload it to go on.';

/** What the mark says on a cell whose change was never sent. */
const NOT_SENT = 'Not sent, because an earlier change was not saved.';

/** What the mark says on a cell whose change may or may not have been saved. */
const OUTCOME_UNKNOWN = 'No answer says whether this change was saved.';
