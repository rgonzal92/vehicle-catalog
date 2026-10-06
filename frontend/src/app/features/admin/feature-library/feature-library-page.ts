import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Textarea } from 'primeng/textarea';
import { FixedLists } from '../../../core/fixed-lists';
import { FeatureKind, KIND_FILTERS, KIND_NAMES } from '../../../core/library';
import { reasonOf } from '../../../shared/reason-of';
import { Feature, FeatureLibrary, FeatureStatus } from './feature-library';

/** The category every package belongs to, and no other feature. */
const PACKAGES = 'PACKAGES';

const STATUS_NAMES: Record<FeatureStatus, string> = { ACTIVE: 'Active', RETIRED: 'Retired' };

/** Where an admin searches the feature library and adds, edits, retires, and reactivates features. */
@Component({
  imports: [
    ReactiveFormsModule,
    RouterLink,
    Button,
    Dialog,
    InputText,
    Message,
    Select,
    TableModule,
    Tag,
    Textarea,
  ],
  selector: 'app-feature-library-page',
  template: `
    <main class="mx-auto max-w-6xl px-6 py-10">
      <a class="text-primary underline" routerLink="/dashboard">Dashboard</a>
      <header class="mt-4 flex items-center justify-between gap-4">
        <h1 class="text-2xl font-semibold">Feature library</h1>
        <p-button label="Add feature" (onClick)="startAdding()" />
      </header>

      <form
        class="mt-6 flex flex-wrap items-end gap-4"
        role="search"
        [formGroup]="filters"
        (ngSubmit)="search()"
      >
        <div class="grid gap-1">
          <label for="feature-query">Code or name</label>
          <input pInputText id="feature-query" type="search" formControlName="query" />
        </div>
        <div class="grid gap-1">
          <label id="feature-category-filter-label" for="feature-category-filter">Category</label>
          <p-select
            inputId="feature-category-filter"
            ariaLabelledBy="feature-category-filter-label"
            formControlName="category"
            optionLabel="name"
            optionValue="code"
            [options]="categoryFilters()"
            (onChange)="search()"
          />
        </div>
        <div class="grid gap-1">
          <label id="feature-kind-filter-label" for="feature-kind-filter">Kind</label>
          <p-select
            inputId="feature-kind-filter"
            ariaLabelledBy="feature-kind-filter-label"
            formControlName="kind"
            optionLabel="name"
            optionValue="code"
            [options]="kindFilters"
            (onChange)="search()"
          />
        </div>
        <div class="grid gap-1">
          <label id="feature-status-filter-label" for="feature-status-filter">Status</label>
          <p-select
            inputId="feature-status-filter"
            ariaLabelledBy="feature-status-filter-label"
            formControlName="status"
            optionLabel="name"
            optionValue="code"
            [options]="statusFilters"
            (onChange)="search()"
          />
        </div>
        <p-button type="submit" label="Search" severity="secondary" />
      </form>

      <p-table
        class="mt-6 block"
        [value]="library.features()"
        [lazy]="true"
        [paginator]="true"
        [rows]="pageSize"
        [totalRecords]="library.total()"
        [(first)]="first"
        (onLazyLoad)="find()"
      >
        <ng-template #header>
          <tr>
            <th scope="col">Code</th>
            <th scope="col">Name</th>
            <th scope="col">Category</th>
            <th scope="col">Kind</th>
            <th scope="col">Status</th>
            <th scope="col"><span class="sr-only">Actions</span></th>
          </tr>
        </ng-template>
        <ng-template #body let-feature>
          <tr>
            <td>{{ feature.code }}</td>
            <td>
              {{ feature.name }}
              @if (feature.description) {
                <p class="text-sm text-muted-color">{{ feature.description }}</p>
              }
            </td>
            <td>{{ fixedLists.categoryName(feature.categoryCode) }}</td>
            <td>{{ kindName(feature) }}</td>
            <td>
              <p-tag
                [value]="statusName(feature)"
                [severity]="feature.status === 'ACTIVE' ? 'success' : 'secondary'"
              />
            </td>
            <td class="text-right whitespace-nowrap">
              <p-button
                label="Edit"
                severity="secondary"
                [text]="true"
                [ariaLabel]="'Edit ' + feature.name"
                (onClick)="startEditing(feature)"
              />
              <p-button
                severity="secondary"
                [text]="true"
                [label]="statusAction(feature)"
                [ariaLabel]="statusAction(feature) + ' ' + feature.name"
                (onClick)="changeStatus(feature)"
              />
            </td>
          </tr>
        </ng-template>
        <ng-template #emptymessage>
          <tr>
            <td colspan="6">No features match.</td>
          </tr>
        </ng-template>
      </p-table>

      <p-dialog
        [header]="editing() ? 'Edit feature' : 'Add feature'"
        closeAriaLabel="Close"
        [modal]="true"
        [style]="{ width: '32rem' }"
        [(visible)]="dialogOpen"
      >
        <form class="grid gap-4" [formGroup]="form" (ngSubmit)="save()">
          @if (refusal()) {
            <p-message severity="error">{{ refusal() }}</p-message>
          }
          @if (!editing()) {
            <div class="grid gap-1">
              <label for="feature-code">Code</label>
              <input pInputText id="feature-code" formControlName="code" autocomplete="off" />
            </div>
            <div class="grid gap-1">
              <label id="feature-kind-label" for="feature-kind">Kind</label>
              <p-select
                inputId="feature-kind"
                ariaLabelledBy="feature-kind-label"
                formControlName="kind"
                optionLabel="name"
                optionValue="code"
                appendTo="body"
                [options]="kinds"
              />
            </div>
          }
          <div class="grid gap-1">
            <label for="feature-name">Name</label>
            <input pInputText id="feature-name" formControlName="name" autocomplete="off" />
          </div>
          <div class="grid gap-1">
            <label for="feature-description">Description</label>
            <textarea
              pTextarea
              id="feature-description"
              formControlName="description"
              rows="3"
            ></textarea>
          </div>
          @if (kind() === 'PACKAGE') {
            <p>A package belongs to the Packages category.</p>
          } @else {
            <div class="grid gap-1">
              <label id="feature-category-label" for="feature-category">Category</label>
              <p-select
                inputId="feature-category"
                ariaLabelledBy="feature-category-label"
                formControlName="categoryCode"
                optionLabel="name"
                optionValue="code"
                appendTo="body"
                [options]="featureCategories()"
              />
            </div>
          }
          <div class="flex justify-end gap-2">
            <p-button label="Cancel" severity="secondary" (onClick)="dialogOpen.set(false)" />
            <p-button type="submit" label="Save" [disabled]="form.invalid" />
          </div>
        </form>
      </p-dialog>
    </main>
  `,
})
export class FeatureLibraryPage {
  protected readonly library = inject(FeatureLibrary);
  protected readonly fixedLists = inject(FixedLists);
  private readonly messages = inject(MessageService);
  private readonly forms = inject(NonNullableFormBuilder);

  protected readonly kinds = namedCodes(KIND_NAMES);

  /** Each filter starts with the choice that lets every feature through. */
  protected readonly kindFilters = KIND_FILTERS;
  protected readonly statusFilters = [
    { code: '', name: 'Every status' },
    ...namedCodes(STATUS_NAMES),
  ];
  protected readonly categoryFilters = this.fixedLists.categoryFilters;

  /** The categories a feature that is not a package can be in. */
  protected readonly featureCategories = computed(() =>
    this.fixedLists.categories().filter((category) => category.code !== PACKAGES),
  );

  protected readonly filters = this.forms.group({
    query: '',
    category: '',
    kind: '' as FeatureKind | '',
    status: '' as FeatureStatus | '',
  });

  protected readonly pageSize = 25;

  /** The position of the page's first feature among everything the search found. */
  protected readonly first = signal(0);

  protected readonly dialogOpen = signal(false);

  /** The feature the dialog is editing, or null while it adds one. */
  protected readonly editing = signal<Feature | null>(null);

  /** Why the backend refused the last save, shown in the dialog. */
  protected readonly refusal = signal('');

  protected readonly form = this.forms.group({
    code: ['', Validators.required],
    kind: 'FEATURE' as FeatureKind,
    name: ['', Validators.required],
    description: '',
    categoryCode: ['', Validators.required],
  });

  /** The kind the dialog is showing, which decides whether a category can be chosen. */
  protected readonly kind = toSignal(this.form.controls.kind.valueChanges, {
    initialValue: this.form.controls.kind.value,
  });

  constructor() {
    void this.fixedLists.load();

    // A package is always in Packages, and nothing else is.
    this.form.controls.kind.valueChanges.pipe(takeUntilDestroyed()).subscribe((kind) => {
      const category = this.form.controls.categoryCode;
      if ((kind === 'PACKAGE') !== (category.value === PACKAGES)) {
        category.setValue(kind === 'PACKAGE' ? PACKAGES : '');
      }
    });
  }

  protected kindName(feature: Feature): string {
    return KIND_NAMES[feature.kind];
  }

  protected statusName(feature: Feature): string {
    return STATUS_NAMES[feature.status];
  }

  /** Starts the search over from its first page. */
  protected search(): void {
    this.first.set(0);
    void this.find();
  }

  /** Shows the page the table is on. The table asks for this on its first display and on paging. */
  protected find(): Promise<void> {
    return this.library.find({
      ...this.filters.getRawValue(),
      page: this.first() / this.pageSize,
      size: this.pageSize,
    });
  }

  protected startAdding(): void {
    this.open(null);
    this.form.reset();
    this.form.controls.code.enable();
  }

  protected startEditing(feature: Feature): void {
    this.open(feature);
    this.form.reset(feature);
    // A code never changes, so it takes no part in an edit.
    this.form.controls.code.disable();
  }

  protected async save(): Promise<void> {
    const { code, kind, name, description, categoryCode } = this.form.getRawValue();
    const feature = this.editing();

    try {
      if (feature) {
        const { id, version } = feature;
        await this.library.change(id, { name, description, categoryCode, version });
      } else {
        await this.library.add({ code, kind, name, description, categoryCode });
      }
      this.dialogOpen.set(false);
    } catch (error) {
      this.refusal.set(reasonOf(error));
    }
  }

  /** What the row's status button does: an active feature can be retired, a retired one reactivated. */
  protected statusAction(feature: Feature): string {
    return feature.status === 'ACTIVE' ? 'Retire' : 'Reactivate';
  }

  protected async changeStatus(feature: Feature): Promise<void> {
    try {
      await (feature.status === 'ACTIVE'
        ? this.library.retire(feature.id)
        : this.library.reactivate(feature.id));
    } catch (error) {
      this.messages.add({ severity: 'error', summary: feature.name, detail: reasonOf(error) });
    }
  }

  private open(feature: Feature | null): void {
    this.editing.set(feature);
    this.refusal.set('');
    this.dialogOpen.set(true);
  }
}

/** The entries of a code-to-name table as the options of a dropdown. */
function namedCodes<Code extends string>(names: Record<Code, string>) {
  return (Object.keys(names) as Code[]).map((code) => ({ code, name: names[code] }));
}
