import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Catalog, CatalogEdit, Catalogs } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { FeatureKind, KIND_NAMES, Library, LibraryFeature } from '../../core/library';
import { reasonOf } from '../../shared/reason-of';
import { counted } from './counted';

/**
 * Where the owner of a working copy picks library features to add as feature rows: a search by
 * code or name with filters on category and kind, a page at a time, and a box to tick for each
 * feature wanted. Only active features are on offer, and one that is a row already cannot be
 * picked again. What is ticked stays ticked from page to page and from search to search.
 */
@Component({
  imports: [ReactiveFormsModule, Button, Dialog, InputText, Message, Select, TableModule],
  selector: 'app-add-features-dialog',
  template: `
    <p-dialog
      header="Add features"
      closeAriaLabel="Close"
      [modal]="true"
      [style]="{ width: '60rem' }"
      [(visible)]="visible"
    >
      <div class="grid gap-4">
        @if (refusal()) {
          <p-message severity="error">{{ refusal() }}</p-message>
        }
        <form
          class="flex flex-wrap items-end gap-4"
          role="search"
          [formGroup]="filters"
          (ngSubmit)="search()"
        >
          <div class="grid gap-1">
            <label for="pick-query">Code or name</label>
            <input pInputText id="pick-query" type="search" formControlName="query" />
          </div>
          <div class="grid gap-1">
            <label id="pick-category-label" for="pick-category">Category</label>
            <p-select
              inputId="pick-category"
              ariaLabelledBy="pick-category-label"
              formControlName="category"
              optionLabel="name"
              optionValue="code"
              appendTo="body"
              [options]="categories()"
              (onChange)="search()"
            />
          </div>
          <div class="grid gap-1">
            <label id="pick-kind-label" for="pick-kind">Kind</label>
            <p-select
              inputId="pick-kind"
              ariaLabelledBy="pick-kind-label"
              formControlName="kind"
              optionLabel="name"
              optionValue="code"
              appendTo="body"
              [options]="kinds"
              (onChange)="search()"
            />
          </div>
          <p-button type="submit" label="Search" severity="secondary" />
        </form>

        <p-table
          size="small"
          [value]="found().items"
          [lazy]="true"
          [lazyLoadOnInit]="false"
          [paginator]="true"
          [rows]="pageSize"
          [totalRecords]="found().total"
          [(first)]="first"
          (onLazyLoad)="find()"
        >
          <ng-template #header>
            <tr>
              <th scope="col"><span class="sr-only">Add</span></th>
              <th scope="col">Code</th>
              <th scope="col">Name</th>
              <th scope="col">Category</th>
              <th scope="col">Kind</th>
            </tr>
          </ng-template>
          <ng-template #body let-feature>
            <tr>
              <td>
                <input
                  type="checkbox"
                  class="size-4"
                  [checked]="rows().has(feature.id) || chosen().has(feature.id)"
                  [disabled]="rows().has(feature.id)"
                  [attr.aria-label]="'Add ' + feature.name"
                  (change)="choose(feature.id, $any($event.target).checked)"
                />
              </td>
              <td>{{ feature.code }}</td>
              <td>
                {{ feature.name }}
                @if (rows().has(feature.id)) {
                  <span class="text-muted-color">(already a row)</span>
                }
              </td>
              <td>{{ fixedLists.categoryName(feature.categoryCode) }}</td>
              <td>{{ kindNames[kindOf(feature)] }}</td>
            </tr>
          </ng-template>
          <ng-template #emptymessage>
            <tr>
              <td colspan="5">No active features match.</td>
            </tr>
          </ng-template>
        </p-table>

        <div class="flex items-center justify-end gap-4">
          <span aria-live="polite" data-chosen>{{ chosenInWords() }}</span>
          <p-button label="Cancel" severity="secondary" (onClick)="visible.set(false)" />
          <p-button
            label="Add"
            [disabled]="chosen().size === 0"
            [loading]="adding()"
            (onClick)="add()"
          />
        </div>
      </div>
    </p-dialog>
  `,
})
export class AddFeaturesDialog {
  private readonly catalogs = inject(Catalogs);
  private readonly library = inject(Library);
  protected readonly fixedLists = inject(FixedLists);

  /** The working copy the features are added to. */
  readonly catalog = input.required<Catalog>();

  /**
   * Sends an edit behind the saves on their way and reads the catalog again once it is saved. It
   * fails with the backend's refusal when the edit is not saved.
   */
  readonly run = input.required<(edit: CatalogEdit) => Promise<void>>();

  /** Whether the catalog can still be edited. The dialog closes when it no longer can. */
  readonly editable = input.required<boolean>();

  /** Whether the dialog is open. */
  protected readonly visible = signal(false);

  /** Why the backend refused to add the features, shown in the dialog. */
  protected readonly refusal = signal('');

  /** Whether the features are being added, so that a second click adds nothing more. */
  protected readonly adding = signal(false);

  protected readonly pageSize = 10;

  /** The position of the page's first feature among everything the search found. */
  protected readonly first = signal(0);

  /** The page of the search being shown. */
  protected readonly found = signal<{ items: LibraryFeature[]; total: number }>({
    items: [],
    total: 0,
  });

  /** The ids of the features ticked so far, on whichever page or search. */
  protected readonly chosen = signal<ReadonlySet<number>>(new Set());

  protected readonly filters = inject(NonNullableFormBuilder).group({
    query: '',
    category: '',
    kind: '' as FeatureKind | '',
  });

  protected readonly kindNames = KIND_NAMES;
  protected readonly kinds = [
    { code: '', name: 'Every kind' },
    ...Object.entries(KIND_NAMES).map(([code, name]) => ({ code, name })),
  ];
  protected readonly categories = computed(() => [
    { code: '', name: 'Every category' },
    ...this.fixedLists.categories(),
  ]);

  /** The ids of the features that are rows of the catalog already. */
  protected readonly rows = computed(
    () => new Set(this.catalog().snapshot.featureRows.map(({ id }) => id)),
  );

  protected readonly chosenInWords = computed(
    () => `${counted(this.chosen().size, 'feature')} chosen`,
  );

  /** How many searches have been sent, so that a slow answer to an earlier one is dropped. */
  private asked = 0;

  constructor() {
    effect(() => {
      if (!this.editable()) {
        this.visible.set(false);
      }
    });
  }

  /** Opens the dialog with nothing ticked, no filter set, and the first page of features. */
  open(): void {
    this.refusal.set('');
    this.chosen.set(new Set());
    this.filters.reset();
    this.visible.set(true);
    void this.fixedLists.load();
    this.search();
  }

  /** Starts the search over from its first page. */
  protected search(): void {
    this.first.set(0);
    void this.find();
  }

  /** Shows the page the table is on. The table asks for this when it is paged. */
  protected async find(): Promise<void> {
    const mine = ++this.asked;
    try {
      const found = await this.library.activeFeatures({
        ...this.filters.getRawValue(),
        page: this.first() / this.pageSize,
        size: this.pageSize,
      });
      if (mine === this.asked) {
        this.found.set(found);
      }
    } catch {
      // The failure has already been shown as a message.
    }
  }

  protected kindOf(feature: LibraryFeature): FeatureKind {
    return feature.kind;
  }

  /** Ticks or unticks a feature. */
  protected choose(featureId: number, chosen: boolean): void {
    this.chosen.update((before) => {
      const after = new Set(before);
      if (chosen) {
        after.add(featureId);
      } else {
        after.delete(featureId);
      }
      return after;
    });
  }

  /** Adds the ticked features as feature rows and closes, or stays open with the refusal. */
  protected async add(): Promise<void> {
    const featureIds = [...this.chosen()];
    if (featureIds.length === 0 || this.adding()) {
      return;
    }

    this.refusal.set('');
    this.adding.set(true);
    try {
      await this.run()((revision) =>
        this.catalogs.addFeatures(this.catalog().snapshot.catalogId, revision, featureIds),
      );
      this.visible.set(false);
    } catch (error) {
      this.refusal.set(reasonOf(error));
    } finally {
      this.adding.set(false);
    }
  }
}
