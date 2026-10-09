import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormControl,
  FormsModule,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Cog } from '@primeicons/angular/cog';
import { Plus } from '@primeicons/angular/plus';
import { Button, ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Textarea } from 'primeng/textarea';
import { Tab, TabList, TabPanel, TabPanels, Tabs } from 'primeng/tabs';
import { Tag } from 'primeng/tag';
import {
  baseInWords,
  Catalog,
  CatalogEdit,
  Catalogs,
  LONGEST_CATALOG_NAME,
  LONGEST_NOTE,
  STATUS_NAMES,
  STATUS_SEVERITIES,
} from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { FeatureKind, KIND_FILTERS } from '../../core/library';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Cell, FeatureRow, Issue } from '../../shared/availability-matrix/matrix';
import { ExportDialog } from '../../shared/export-dialog';
import { IssueCounts, IssueList } from '../../shared/issues';
import { Loading, ReadFailed } from '../../shared/read-state';
import { reasonOf } from '../../shared/reason-of';
import { AddFeaturesDialog } from './add-features-dialog';
import { counted, sentence } from './counted';
import { HistoryTab } from './history-tab';
import { ManageOfferingsDialog } from './manage-offerings-dialog';
import { rulesInWords } from './rule-words';
import { RulesTab } from './rules-tab';
import { failureOf, NotSent, SaveQueue, SaveStop } from './save-queue';
import { UpdateDialog } from './update-dialog';

/**
 * A catalog as its owner works on it: what describes it, its matrix on the Features tab, its rules
 * on the Rules tab, its issues on the Issues tab, and its change history on the History tab. The owner of a working copy in status Draft renames it in the
 * header. A working copy shows the library's current labels.
 *
 * The owner of a working copy in status Draft sets its cells, adds and removes feature rows, and
 * manages its trims, regions, and offerings, and each change is saved at once, with no save
 * button. They submit it for review when it has no Error, and can withdraw it until a reviewer has
 * decided. Anyone else, and any other status, gets the matrix read-only. Whole regions can be hidden to keep the matrix narrow, and
 * its feature rows narrowed to the ones being worked on.
 *
 * A change that is not saved goes back to what the cell was, marked with the reason. After a
 * revision conflict, or when no answer says whether a change was saved, the editor sends nothing
 * more and takes no further change until the catalog has been reloaded. A catalog that is no longer
 * in status Draft is reloaded at once, read-only.
 */
@Component({
  imports: [
    DatePipe,
    ReactiveFormsModule,
    Button,
    ButtonDirective,
    ButtonIcon,
    ButtonLabel,
    Checkbox,
    Cog,
    Plus,
    FormsModule,
    Dialog,
    InputText,
    Message,
    Select,
    Textarea,
    Tab,
    TabList,
    TabPanel,
    TabPanels,
    Tabs,
    Tag,
    AvailabilityMatrix,
    ExportDialog,
    IssueCounts,
    IssueList,
    Loading,
    ReadFailed,
    AddFeaturesDialog,
    HistoryTab,
    ManageOfferingsDialog,
    RulesTab,
    UpdateDialog,
  ],
  selector: 'app-catalog-editor-page',
  template: `
    <div class="grid gap-6">
      @if (catalog(); as catalog) {
        <header class="grid gap-3">
          @if (renaming()) {
            <!-- The page keeps its heading while the name is a box to type in. -->
            <h2 class="sr-only">{{ catalog.name }}</h2>
            <form
              class="flex flex-wrap items-center gap-2"
              [formGroup]="newName"
              (ngSubmit)="rename()"
              (keydown.escape)="stopRenaming()"
            >
              <label class="sr-only" for="catalog-name">Name</label>
              <input
                #nameBox
                pInputText
                id="catalog-name"
                class="w-96 max-w-full"
                autocomplete="off"
                formControlName="name"
              />
              <p-button
                type="submit"
                label="Save"
                [disabled]="newName.invalid"
                [loading]="renamingNow()"
              />
              <p-button label="Cancel" severity="secondary" (onClick)="stopRenaming()" />
            </form>
            @if (renameRefusal()) {
              <p-message class="block" severity="error">{{ renameRefusal() }}</p-message>
            }
          } @else {
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="text-2xl font-semibold">{{ catalog.name }}</h2>
              @if (editable()) {
                <p-button
                  #renameButton
                  label="Rename"
                  severity="secondary"
                  size="small"
                  [text]="true"
                  (onClick)="startRenaming(catalog)"
                />
              }
            </div>
          }
          @if (catalog.owned && catalog.snapshot.status === 'DRAFT') {
            @let blocked = submitBlocked(catalog);
            <div class="flex flex-wrap items-center gap-3">
              <button
                pButton
                type="button"
                [disabled]="!!blocked || !editable()"
                [attr.aria-describedby]="blocked ? 'submit-blocked' : null"
                (click)="startSubmitting()"
              >
                <span pButtonLabel>Submit for review</span>
              </button>
              @if (blocked) {
                <span id="submit-blocked" class="text-sm text-muted-color">{{ blocked }}</span>
              }
            </div>
          }
          @if (catalog.owned && catalog.snapshot.status === 'SUBMITTED') {
            <div class="flex flex-wrap items-center gap-3">
              <p-button
                label="Withdraw"
                severity="secondary"
                [loading]="withdrawing()"
                (onClick)="withdraw()"
              />
              <span class="text-sm text-muted-color">
                This catalog is waiting for review. Withdraw it to edit it again.
              </span>
            </div>
          }
          <div>
            <p-button label="Export" severity="secondary" (onClick)="exporting.start()" />
            <app-export-dialog #exporting [catalogId]="catalog.snapshot.catalogId" />
          </div>
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
              <dt class="text-sm text-muted-color">Status</dt>
              <dd>
                <p-tag
                  [severity]="statusSeverities[catalog.snapshot.status]"
                  [value]="statusNames[catalog.snapshot.status]"
                />
              </dd>
            </div>
            <div>
              <dt class="text-sm text-muted-color">Base</dt>
              <dd data-base>{{ baseInWords(catalog) }}</dd>
            </div>
            @if (!catalog.owned && catalog.snapshot.status !== 'APPROVED') {
              <div>
                <dt class="text-sm text-muted-color">Owner</dt>
                <dd>{{ catalog.owner }}</dd>
              </div>
            }
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
          <app-issue-counts
            aria-live="polite"
            data-issue-counts
            [errors]="errors()"
            [warnings]="warnings()"
          />
        </header>

        @if (catalog.decision; as decided) {
          <p class="notice" role="status" data-notice="decision">
            @if (decided.decision === 'REJECTED') {
              <strong>Rejected by {{ decided.reviewer }}</strong> on
              {{ decided.at | date: 'medium' }}:
              <span class="whitespace-pre-line">{{ decided.comment }}</span>
            } @else {
              <strong>Returned</strong> on {{ decided.at | date: 'medium' }}: another catalog of its
              lineage was approved first.
            }
          </p>
        }
        @if (stale() && catalog.snapshot.status !== 'APPROVED') {
          <div class="notice flex flex-wrap items-center justify-between gap-3">
            <p role="status" data-notice="stale">
              <strong>This catalog is stale.</strong>
              @if (catalog.stale && catalog.current; as current) {
                Approved v{{ current.versionNumber }} is now the current version of
                {{ catalog.vehicleLine }} {{ catalog.modelYear }}, and this catalog was made from an
                earlier one.
              } @else {
                Another version of {{ catalog.vehicleLine }} {{ catalog.modelYear }} was approved
                after this catalog was made.
              }
              It cannot be submitted until it has been updated from that version.
            </p>
            @if (catalog.owned && catalog.snapshot.status === 'DRAFT') {
              <p-button
                label="Update from Approved"
                severity="secondary"
                [disabled]="!editable()"
                [loading]="previewing()"
                (onClick)="previewUpdate()"
              />
            }
          </div>
        }
        @if (catalog.owned && catalog.snapshot.status === 'DRAFT') {
          <!-- It outlives the notice, which goes as soon as the update is saved. -->
          <app-update-dialog [catalogId]="catalog.snapshot.catalogId" [run]="restructure" />
        }
        @if (catalog.vehicleLineActive === false && catalog.snapshot.status !== 'APPROVED') {
          <p class="notice" role="status" data-notice="vehicle-line">
            The vehicle line {{ catalog.vehicleLine }} is deactivated. This catalog cannot be
            submitted until the line is active again.
          </p>
        }

        <p-dialog
          header="Submit for review"
          closeAriaLabel="Close"
          [modal]="true"
          [style]="{ width: '32rem' }"
          [closable]="!submittingNow()"
          [(visible)]="submitting"
        >
          <form class="grid gap-4" (ngSubmit)="submit()">
            @if (submitRefusal()) {
              <p-message severity="error">{{ submitRefusal() }}</p-message>
            }
            <p>
              A reviewer decides on the catalog as it is now. Until then it cannot be edited, and
              you can withdraw it.
            </p>
            <div class="grid gap-1">
              <label for="submit-note">Note for the reviewer (optional)</label>
              <textarea
                pTextarea
                id="submit-note"
                rows="4"
                [maxlength]="longestNote"
                [formControl]="submitNote"
              ></textarea>
            </div>
            <div class="flex justify-end gap-2">
              <p-button
                label="Cancel"
                severity="secondary"
                [disabled]="submittingNow()"
                (onClick)="submitting.set(false)"
              />
              <p-button type="submit" label="Submit" [loading]="submittingNow()" />
            </div>
          </form>
        </p-dialog>

        @if (reloadNeeded(); as why) {
          <p-message class="block" severity="error">
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

        <p-tabs class="surface" [(value)]="tab">
          <p-tablist>
            <p-tab value="features">Features</p-tab>
            <p-tab value="rules">Rules</p-tab>
            <p-tab value="issues">Issues</p-tab>
            <p-tab value="history">History</p-tab>
          </p-tablist>
          <p-tabpanels>
            <p-tabpanel value="features">
              <div class="mb-3 flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
                <form
                  class="flex flex-wrap items-end gap-4"
                  role="search"
                  aria-label="Feature rows shown"
                  [formGroup]="rowFilters"
                >
                  <div class="grid gap-1">
                    <label for="row-query">Code or name</label>
                    <input pInputText id="row-query" type="search" formControlName="query" />
                  </div>
                  <div class="grid gap-1">
                    <label id="row-category-label" for="row-category">Category</label>
                    <p-select
                      inputId="row-category"
                      ariaLabelledBy="row-category-label"
                      formControlName="category"
                      optionLabel="name"
                      optionValue="code"
                      [options]="fixedLists.categoryFilters()"
                    />
                  </div>
                  <div class="grid gap-1">
                    <label id="row-kind-label" for="row-kind">Kind</label>
                    <p-select
                      inputId="row-kind"
                      ariaLabelledBy="row-kind-label"
                      formControlName="kind"
                      optionLabel="name"
                      optionValue="code"
                      [options]="kindFilters"
                    />
                  </div>
                </form>
                @if (editable() || managing()) {
                  <div class="flex flex-wrap gap-2">
                    <button
                      pButton
                      type="button"
                      severity="secondary"
                      [disabled]="managing()"
                      (click)="adding.open()"
                    >
                      <svg data-p-icon="plus" pButtonIcon />
                      <span pButtonLabel>Add features</span>
                    </button>
                    <button
                      pButton
                      type="button"
                      severity="secondary"
                      [disabled]="managing()"
                      (click)="manage(offerings)"
                    >
                      <svg data-p-icon="cog" pButtonIcon />
                      <span pButtonLabel>Manage trims and regions</span>
                    </button>
                  </div>
                }
              </div>
              <div class="mb-2 flex flex-wrap items-center justify-between gap-x-6 gap-y-2">
                <div>
                  @if (catalog.snapshot.regions.length > 0) {
                    <fieldset class="flex flex-wrap items-center gap-4">
                      <legend class="float-left mr-4 text-sm text-muted-color">
                        Regions shown
                      </legend>
                      @for (region of catalog.snapshot.regions; track region.code) {
                        <label class="flex items-center gap-2">
                          <p-checkbox
                            [binary]="true"
                            [ngModel]="!hiddenRegions().has(region.code)"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="showRegion(region.code, $event)"
                          />
                          {{ region.name }}
                        </label>
                      }
                    </fieldset>
                  }
                </div>
                <p class="text-sm text-muted-color" aria-live="polite" data-rows-shown>
                  {{ rowsShownInWords() }}
                </p>
              </div>
              <app-availability-matrix
                #matrix
                class="h-[70vh] min-h-96"
                [contents]="catalog.snapshot"
                [categories]="fixedLists.categories()"
                [editable]="editable()"
                [hiddenRegions]="hiddenRegions()"
                [featureFilter]="featureFilter()"
                [issues]="issues()"
                (cellChange)="save($event)"
                (featureRemove)="askToRemove($event)"
              />
              <app-add-features-dialog
                #adding
                [catalog]="catalog"
                [run]="restructure"
                [editable]="editable()"
              />
              <p-dialog
                header="Remove feature row"
                closeAriaLabel="Close"
                [modal]="true"
                [style]="{ width: '30rem' }"
                [closable]="!removingNow()"
                [visible]="removing() !== null"
                (visibleChange)="removing.set(null)"
                (onHide)="matrix.focusOn(lastAsked)"
              >
                @if (removing(); as asked) {
                  @let named = rulesNaming(asked.feature, catalog);
                  <div class="grid gap-4">
                    @if (removalRefusal()) {
                      <p-message severity="error">{{ removalRefusal() }}</p-message>
                    }
                    <p data-question>
                      Remove {{ asked.feature.name }} ({{ asked.feature.code }}) from this catalog?
                      {{ cellsGoing(asked.cells) }}
                    </p>
                    @if (named.length > 0) {
                      <div class="grid gap-1" data-rules-going>
                        <p>{{ rulesGoing(named.length) }}</p>
                        <ul class="list-disc pl-6">
                          @for (rule of named; track rule) {
                            <li>{{ rule }}</li>
                          }
                        </ul>
                      </div>
                    }
                    <div class="flex justify-end gap-2">
                      <p-button
                        label="Keep"
                        severity="secondary"
                        [autofocus]="true"
                        [disabled]="removingNow()"
                        (onClick)="removing.set(null)"
                      />
                      <p-button
                        [label]="named.length > 0 ? 'Remove with its rules' : 'Remove'"
                        [loading]="removingNow()"
                        (onClick)="removeFeature(asked.feature, named.length > 0)"
                      />
                    </div>
                  </div>
                }
              </p-dialog>
              <app-manage-offerings-dialog
                #offerings
                [catalog]="catalog"
                [run]="restructure"
                [editable]="editable()"
              />
            </p-tabpanel>
            <p-tabpanel value="rules">
              @if (tab() === 'rules') {
                <app-rules-tab
                  [catalog]="catalog"
                  [run]="restructure"
                  [editable]="editable()"
                  [shown]="shownRule()"
                />
              }
            </p-tabpanel>
            <p-tabpanel value="issues">
              <app-issue-list
                [issues]="issues()"
                [contents]="catalog.snapshot"
                [showsRules]="true"
                (showCell)="showCell($event, matrix)"
                (showRule)="showRule($event)"
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
        <p>There is no catalog at this address.</p>
      } @else if (failed()) {
        <app-read-failed what="The catalog could not be read." (again)="reload()" />
      } @else {
        <app-loading class="surface" />
      }
    </div>
  `,
})
export class CatalogEditorPage {
  private readonly catalogs = inject(Catalogs);
  protected readonly fixedLists = inject(FixedLists);
  private readonly messages = inject(MessageService);
  private readonly injector = inject(Injector);
  protected readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly catalog = signal<Catalog | null>(null);

  /** The catalog's issues as the latest answer about it gave them, Errors before Warnings. */
  protected readonly issues = computed(() => this.catalogs.issuesOf(this.id));
  protected readonly errors = computed(
    () => this.issues().filter(({ severity }) => severity === 'ERROR').length,
  );
  protected readonly warnings = computed(() => this.issues().length - this.errors());

  /**
   * Whether the catalog is stale, as the latest answer about it said: its lineage's current
   * Approved is no longer the version it was made from.
   */
  protected readonly stale = computed(() => this.catalogs.staleOf(this.id));

  /** Whether the address names no catalog, or one this person may not open. */
  protected readonly missing = signal(false);

  /** Whether the catalog could not be read at all, which the page then says. */
  protected readonly failed = signal(false);

  protected readonly statusNames = STATUS_NAMES;
  protected readonly statusSeverities = STATUS_SEVERITIES;

  /** The tab being shown. */
  protected readonly tab = signal<string | number | undefined>('features');

  /**
   * The key of the rule of the catalog that an issue was chosen to show, which the Rules tab
   * brings into view, or null. It lasts while the Rules tab is shown.
   */
  protected readonly shownRule = signal<string | null>(null);

  /** The matrix, which is told how the save of each change it reported went. */
  private readonly matrix = viewChild<AvailabilityMatrix>('matrix');

  /**
   * Sends this page's edits in the order they were made, one at a time. Each time the catalog is
   * read, it gets a new one that starts from the revision read.
   */
  private readonly saves = signal<SaveQueue<CatalogEdit> | null>(null);

  /**
   * What narrows the matrix to the feature rows the person is working on: part of a code or a
   * name, a category, and a kind. It is a way of looking at the catalog and changes nothing in it.
   */
  protected readonly rowFilters = inject(NonNullableFormBuilder).group({
    query: '',
    category: '',
    kind: '' as FeatureKind | '',
  });

  private readonly rowFilter = toSignal(this.rowFilters.valueChanges, {
    initialValue: this.rowFilters.getRawValue(),
  });

  /** Whether a feature row gets through what the person has narrowed the matrix to. */
  protected readonly featureFilter = computed(() => {
    const { query = '', category, kind } = this.rowFilter();
    const sought = query.trim().toLowerCase();

    return (feature: FeatureRow) =>
      (!sought ||
        feature.code.toLowerCase().includes(sought) ||
        feature.name.toLowerCase().includes(sought)) &&
      (!category || feature.categoryCode === category) &&
      (!kind || feature.kind === kind);
  });

  /** How many of the catalog's feature rows get through what the matrix is narrowed to. */
  protected readonly rowsShownInWords = computed(() => {
    const rows = this.catalog()?.snapshot.featureRows ?? [];
    const shown = rows.filter(this.featureFilter()).length;

    return `${shown} of ${rows.length} feature ${rows.length === 1 ? 'row' : 'rows'} shown`;
  });

  protected readonly kindFilters = KIND_FILTERS;

  /** Whether the name in the header is being edited in place. */
  protected readonly renaming = signal(false);

  /** The name being typed while the catalog is renamed. */
  protected readonly newName = inject(NonNullableFormBuilder).group({
    // A name is judged without the spaces around it, so one of spaces alone is no name.
    name: [
      '',
      [Validators.required, Validators.pattern(/\S/), Validators.maxLength(LONGEST_CATALOG_NAME)],
    ],
  });

  /** Whether the new name is on its way, so that a second Enter saves nothing more. */
  protected readonly renamingNow = signal(false);

  /** Why the backend refused the new name, shown under it. */
  protected readonly renameRefusal = signal('');

  private readonly nameBox = viewChild<ElementRef<HTMLInputElement>>('nameBox');
  private readonly renameButton = viewChild('renameButton', { read: ElementRef });

  /** Whether the dialog that submits the catalog for review is open. */
  protected readonly submitting = signal(false);

  /** What the owner says to the reviewer, if anything. */
  protected readonly submitNote = new FormControl('', { nonNullable: true });
  protected readonly longestNote = LONGEST_NOTE;

  /** Why the backend refused the submit, shown in the dialog. */
  protected readonly submitRefusal = signal('');

  /** Whether the submit is on its way, so that a second click submits nothing more. */
  protected readonly submittingNow = signal(false);

  /** Whether a withdrawal is on its way. */
  protected readonly withdrawing = signal(false);

  /** Whether an update from Approved is being worked out. */
  protected readonly previewing = signal(false);

  private readonly updateDialog = viewChild(UpdateDialog);

  /** The feature row the person is asked to confirm the removal of, or null while there is none. */
  protected readonly removing = signal<{ feature: FeatureRow; cells: number } | null>(null);

  /** Why the backend refused to remove the feature row, shown with the question. */
  protected readonly removalRefusal = signal('');

  /** Whether the removal is on its way, so that a second click removes nothing more. */
  protected readonly removingNow = signal(false);

  /**
   * The feature row the person was last asked about. Once the question has gone from the page, the
   * focus goes back to the row's remove button, or to where the keyboard starts in the matrix when
   * the row is gone.
   */
  protected lastAsked?: FeatureRow;

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
   * Whether the catalog could not be read again after a change of what its matrix is made of (its
   * feature rows, trims, regions, or offerings) was saved. What the page shows is then behind what is saved.
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
    effect(() => {
      if (this.tab() !== 'rules') {
        this.shownRule.set(null);
      }
    });
    void this.fixedLists.load();
    void this.open();
  }

  protected readonly baseInWords = baseInWords;

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
   * Sends a change of the catalog's feature rows, trims, regions, offerings, or rules behind the
   * saves on their way, and reads the catalog again once it is saved, since such a change alters
   * what the page is made of. It fails with the backend's refusal when the change is not saved.
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

  /** Turns the name in the header into a box to type the new name in. */
  protected startRenaming(catalog: Catalog): void {
    this.newName.setValue({ name: catalog.name });
    this.renameRefusal.set('');
    this.renaming.set(true);
    afterNextRender(
      () => {
        const box = this.nameBox()?.nativeElement;
        box?.focus();
        box?.select();
      },
      { injector: this.injector },
    );
  }

  /** Turns the box back into the name, and puts the focus back on the button that opened it. */
  protected stopRenaming(): void {
    this.renaming.set(false);
    afterNextRender(
      () =>
        (this.renameButton()?.nativeElement as HTMLElement | undefined)
          ?.querySelector('button')
          ?.focus(),
      { injector: this.injector },
    );
  }

  /** Saves the new name behind the saves on their way, or keeps the box open with the refusal. */
  protected async rename(): Promise<void> {
    const saves = this.saves();
    const name = this.newName.getRawValue().name.trim();
    if (!saves || this.newName.invalid || this.renamingNow()) {
      return;
    }

    this.renamingNow.set(true);
    try {
      const changed = await saves.add((revision) => this.catalogs.rename(this.id, revision, name));
      if (changed) {
        this.catalog.update((catalog) => catalog && { ...catalog, name });
      } else {
        this.noNewChanges();
      }
      this.stopRenaming();
    } catch (error) {
      if (failureOf(error) === 'rejected') {
        this.renameRefusal.set(reasonOf(error));
        return;
      }
      // The editor has stopped, which the banner says.
      this.stopRenaming();
      if (saves.stopped() === 'closed') {
        await this.closed(reasonOf(error));
      }
    } finally {
      this.renamingNow.set(false);
    }
  }

  /**
   * Why the catalog cannot be submitted as it is, which the page says beside the button, or nothing
   * when it can be.
   */
  protected submitBlocked(catalog: Catalog): string {
    if (this.stale()) {
      return 'It is stale, and has to be updated from the current Approved version first.';
    }
    if (catalog.vehicleLineActive === false) {
      return 'Its vehicle line is deactivated.';
    }
    const errors = this.errors();
    return errors === 0
      ? ''
      : `It has ${errors === 1 ? '1 Error' : `${errors} Errors`}, which must be put right first.`;
  }

  protected startSubmitting(): void {
    this.submitNote.reset();
    this.submitRefusal.set('');
    this.submitting.set(true);
  }

  /**
   * Submits the catalog behind the saves on their way, and reads it again as the Submitted catalog
   * it then is. When the backend refuses, the dialog stays open with the reason.
   */
  protected async submit(): Promise<void> {
    if (this.submittingNow()) {
      return;
    }

    const note = this.submitNote.value.trim();
    this.submitRefusal.set('');
    this.submittingNow.set(true);
    try {
      await this.restructure((revision) => this.catalogs.submit(this.id, revision, note));
      this.submitting.set(false);
    } catch (error) {
      if (failureOf(error) === 'rejected') {
        this.submitRefusal.set(reasonOf(error));
      } else {
        // The editor has stopped or read the catalog again, and says so itself.
        this.submitting.set(false);
      }
    } finally {
      this.submittingNow.set(false);
    }
  }

  /**
   * Opens the dialog that shows what an update from Approved would do. It is worked out from the
   * catalog as it is saved, so the saves on their way go first.
   */
  protected async previewUpdate(): Promise<void> {
    const saves = this.saves();
    this.previewing.set(true);
    try {
      await saves?.whenIdle();
      if (!saves?.stopped()) {
        await this.updateDialog()?.open();
      }
    } finally {
      this.previewing.set(false);
    }
  }

  /** Makes the Submitted catalog a Draft again, and reads it again either way. */
  protected async withdraw(): Promise<void> {
    if (this.withdrawing()) {
      return;
    }

    this.withdrawing.set(true);
    try {
      await this.catalogs.withdraw(this.id);
    } catch (error) {
      this.messages.add({ severity: 'error', summary: 'Not withdrawn', detail: reasonOf(error) });
    }
    await this.open();
    this.withdrawing.set(false);
  }

  /** Asks the person to confirm the removal of a feature row, which takes its cells along. */
  protected askToRemove(asked: { feature: FeatureRow; cells: number }): void {
    this.removalRefusal.set('');
    this.lastAsked = asked.feature;
    this.removing.set(asked);
  }

  /** What the question says about the cells that go with a feature row. */
  protected cellsGoing(cells: number): string {
    return `${sentence(counted(cells, 'cell'))} ${cells === 1 ? 'goes' : 'go'} with it.`;
  }

  /** The rules of the catalog that name a feature, in words, which go when its row is removed. */
  protected rulesNaming(feature: FeatureRow, { snapshot }: Catalog): string[] {
    return rulesInWords(
      snapshot,
      (rule) =>
        rule.sourceFeatureId === feature.id ||
        // Of a pair, the rule that starts from the feature says it for both.
        (rule.pairKey === null && rule.targetFeatureIds.includes(feature.id)),
    );
  }

  /** What the question says about the rules that go with a feature row. */
  protected rulesGoing(rules: number): string {
    return rules === 1
      ? '1 rule of this catalog names it. Removing the row deletes the rule too:'
      : `${rules} rules of this catalog name it. Removing the row deletes them too:`;
  }

  /**
   * Removes the feature row the person confirmed, with the rules that name it when there are any,
   * or keeps the question open with the refusal.
   */
  protected async removeFeature(feature: FeatureRow, withItsRules = false): Promise<void> {
    if (this.removingNow()) {
      return;
    }

    this.removingNow.set(true);
    try {
      await this.restructure(
        async (revision) =>
          (await this.catalogs.removeFeature(this.id, revision, feature.id, withItsRules)).revision,
      );
      this.removing.set(null);
    } catch (error) {
      if (failureOf(error) === 'rejected') {
        this.removalRefusal.set(reasonOf(error));
      } else {
        // The editor has stopped or read the catalog again, and says so itself.
        this.removing.set(null);
      }
    } finally {
      this.removingNow.set(false);
    }
  }

  /**
   * Shows the Features tab with the cell an issue is about in view. A filter on the rows, or a
   * region left out of the matrix, would keep the cell from view, so both give way.
   */
  protected showCell(issue: Issue, matrix: AvailabilityMatrix): void {
    const { featureId, trimId, regionCode } = issue;
    if (featureId === null || trimId === null || regionCode === null) {
      return;
    }
    this.rowFilters.reset();
    this.showRegion(regionCode, true);
    this.tab.set('features');
    afterNextRender(() => matrix.show({ featureId, trimId, regionCode }), {
      injector: this.injector,
    });
  }

  /** Shows the Rules tab with the rule of the catalog that an issue comes from in view. */
  protected showRule(issue: Issue): void {
    if (issue.rule) {
      this.shownRule.set(issue.rule.key);
      this.tab.set('rules');
    }
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
    this.failed.set(false);
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
      // A catalog that was never read leaves nothing to show. One that was read stays in view.
      this.failed.set(!this.missing() && !this.catalog());
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
