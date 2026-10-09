import { Component, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Pencil } from '@primeicons/angular/pencil';
import { Plus } from '@primeicons/angular/plus';
import { Search } from '@primeicons/angular/search';
import { Trash } from '@primeicons/angular/trash';
import { usePreset } from '@primeuix/themes';
import { ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { SelectButton } from 'primeng/selectbutton';
import { TableModule } from 'primeng/table';
import { Tab, TabList, TabPanel, TabPanels, Tabs } from 'primeng/tabs';
import { Tag } from 'primeng/tag';
import { Textarea } from 'primeng/textarea';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { CATEGORIES, largestCatalog } from '../matrix-proof/matrix-proof-page';
import { LOOKS } from './looks';

/** The fonts of the looks. This page alone asks another site for them. */
const FONTS =
  'https://fonts.googleapis.com/css2?family=Inter:wght@400..700&family=IBM+Plex+Sans:wght@400;500;600;700&family=Figtree:wght@400..700&display=swap';

/**
 * Shows the app's building blocks in each of the looks it could take, so that one can be chosen.
 * Choosing a look restyles the whole app until the page is reloaded. The page exists only in
 * development builds.
 */
@Component({
  imports: [
    FormsModule,
    Pencil,
    Plus,
    Search,
    Trash,
    ButtonDirective,
    ButtonIcon,
    ButtonLabel,
    Checkbox,
    Dialog,
    InputText,
    Message,
    Select,
    SelectButton,
    TableModule,
    Tab,
    TabList,
    TabPanel,
    TabPanels,
    Tabs,
    Tag,
    Textarea,
    AvailabilityMatrix,
  ],
  selector: 'app-looks-page',
  template: `
    <div class="grid gap-6">
      <section class="grid gap-3" aria-labelledby="look">
        <h2 id="look" class="text-xl font-semibold">Look</h2>
        <p-selectbutton
          optionLabel="name"
          ariaLabelledBy="look"
          [options]="looks"
          [allowEmpty]="false"
          [ngModel]="look()"
          (ngModelChange)="use($event)"
        />
        <p class="text-muted-color">
          {{ look().says }} The switch in the top strip changes light and dark.
        </p>
      </section>

      <section [class]="surface" aria-labelledby="catalogs">
        <div [class]="surfaceHeader">
          <h2 id="catalogs" class="font-semibold">My catalogs</h2>
          <button pButton type="button" (click)="asking.set(true)">
            <svg data-p-icon="plus" pButtonIcon />
            <span pButtonLabel>New catalog</span>
          </button>
        </div>
        <p-table [value]="catalogs">
          <ng-template #header>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Vehicle line</th>
              <th scope="col">Model year</th>
              <th scope="col">Status</th>
              <th scope="col"><span class="sr-only">Actions</span></th>
            </tr>
          </ng-template>
          <ng-template #body let-catalog>
            <tr>
              <td>
                <a class="font-medium text-primary hover:underline" href="/dev/looks">{{
                  catalog.name
                }}</a>
              </td>
              <td>{{ catalog.vehicleLine }}</td>
              <td>{{ catalog.modelYear }}</td>
              <td><p-tag [severity]="catalog.severity" [value]="catalog.status" /></td>
              <td class="text-right whitespace-nowrap">
                <button pButton type="button" severity="secondary" size="small" [text]="true">
                  <svg data-p-icon="pencil" pButtonIcon />
                  <span pButtonLabel>Rename</span>
                </button>
                <button pButton type="button" severity="secondary" size="small" [text]="true">
                  <svg data-p-icon="trash" pButtonIcon />
                  <span pButtonLabel>Delete</span>
                </button>
              </td>
            </tr>
          </ng-template>
        </p-table>
      </section>

      <section [class]="surface" aria-labelledby="queue">
        <div [class]="surfaceHeader">
          <h2 id="queue" class="font-semibold">Review queue</h2>
        </div>
        <p class="px-4 py-8 text-center text-muted-color">Nothing is waiting for review.</p>
      </section>

      <section [class]="surface" aria-labelledby="form">
        <div [class]="surfaceHeader">
          <h2 id="form" class="font-semibold">A form</h2>
        </div>
        <div class="grid max-w-xl gap-4 p-4">
          <p-message severity="error">The name is already used by another working copy.</p-message>
          <div class="grid gap-1">
            <label for="look-name">Name</label>
            <input pInputText id="look-name" value="Winter update" />
          </div>
          <div class="grid gap-1">
            <label id="look-line-label" for="look-line">Vehicle line</label>
            <p-select
              inputId="look-line"
              ariaLabelledBy="look-line-label"
              appendTo="body"
              [options]="lines"
              [ngModel]="lines[0]"
            />
          </div>
          <div class="grid gap-1">
            <label for="look-note">Description</label>
            <textarea pTextarea id="look-note" rows="2">Heated seats become Standard.</textarea>
          </div>
          <label class="flex items-center gap-2">
            <p-checkbox [binary]="true" [ngModel]="true" />
            Europe
          </label>
          <div class="flex justify-end gap-2">
            <button pButton type="button" severity="secondary">
              <span pButtonLabel>Cancel</span>
            </button>
            <button pButton type="button"><span pButtonLabel>Save</span></button>
          </div>
        </div>
      </section>

      <p-dialog
        header="New catalog"
        closeAriaLabel="Close"
        [modal]="true"
        [style]="{ width: '28rem' }"
        [(visible)]="asking"
      >
        <div class="grid gap-4">
          <div class="grid gap-1">
            <label for="look-new-name">Name</label>
            <input pInputText id="look-new-name" />
          </div>
          <div class="grid gap-1">
            <label id="look-new-line-label" for="look-new-line">Vehicle line</label>
            <p-select
              inputId="look-new-line"
              ariaLabelledBy="look-new-line-label"
              appendTo="body"
              placeholder="Choose a vehicle line"
              [options]="lines"
            />
          </div>
          <p>Starts from Approved v2</p>
          <div class="flex justify-end gap-2">
            <button pButton type="button" severity="secondary" (click)="asking.set(false)">
              <span pButtonLabel>Cancel</span>
            </button>
            <button pButton type="button" (click)="asking.set(false)">
              <span pButtonLabel>Create</span>
            </button>
          </div>
        </div>
      </p-dialog>

      <section [class]="surface" aria-labelledby="matrix">
        <div [class]="surfaceHeader">
          <h2 id="matrix" class="font-semibold">A catalog's tabs, filters, and matrix</h2>
          <div class="flex gap-2">
            <button pButton type="button" severity="secondary" (click)="editable.set(!editable())">
              <span pButtonLabel>{{ editable() ? 'Show read-only' : 'Show editable' }}</span>
            </button>
            <button pButton type="button" severity="secondary" (click)="markNotSaved()">
              <span pButtonLabel>Mark a cell not saved</span>
            </button>
          </div>
        </div>
        <p-tabs value="features">
          <p-tablist>
            <p-tab value="features">Features</p-tab>
            <p-tab value="history">History</p-tab>
          </p-tablist>
          <p-tabpanels>
            <p-tabpanel value="features">
              <form class="mb-2 flex flex-wrap items-end gap-4" role="search">
                <div class="grid gap-1">
                  <label for="look-query">Code or name</label>
                  <input pInputText id="look-query" />
                </div>
                <div class="grid gap-1">
                  <label id="look-category-label" for="look-category">Category</label>
                  <p-select
                    inputId="look-category"
                    ariaLabelledBy="look-category-label"
                    appendTo="body"
                    optionLabel="name"
                    placeholder="Every category"
                    [options]="categories"
                  />
                </div>
                <button pButton type="button" severity="secondary">
                  <svg data-p-icon="search" pButtonIcon />
                  <span pButtonLabel>Search</span>
                </button>
              </form>
              <app-availability-matrix
                #matrix
                class="h-[28rem]"
                [contents]="contents"
                [categories]="categories"
                [editable]="editable()"
              />
            </p-tabpanel>
            <p-tabpanel value="history">
              <p class="py-8 text-center text-muted-color">
                No changes have been made to this catalog.
              </p>
            </p-tabpanel>
          </p-tabpanels>
        </p-tabs>
      </section>
    </div>
  `,
})
export class LooksPage {
  private readonly matrix = viewChild.required<AvailabilityMatrix>('matrix');

  protected readonly looks = LOOKS;
  protected readonly look = signal(LOOKS[0]);
  protected readonly asking = signal(false);
  protected readonly editable = signal(true);

  protected readonly contents = largestCatalog();
  protected readonly categories = CATEGORIES;
  protected readonly lines = ['Compact SUV', 'Pickup Truck', 'Sedan'];
  protected readonly catalogs = [
    { name: 'Winter update', vehicleLine: 'Compact SUV', modelYear: 2027, status: 'Draft' },
    { name: 'Tow refresh', vehicleLine: 'Pickup Truck', modelYear: 2026, status: 'Submitted' },
    { name: 'Launch content', vehicleLine: 'Sedan', modelYear: 2027, status: 'Approved' },
  ].map((catalog) => ({
    ...catalog,
    severity: { Draft: 'secondary', Submitted: 'info', Approved: 'success' }[catalog.status],
  }));

  /** A part of a page: a bordered panel on the page's ground. */
  protected readonly surface =
    'overflow-hidden rounded-border border border-surface bg-surface-0 dark:bg-surface-900';
  protected readonly surfaceHeader =
    'flex flex-wrap items-center justify-between gap-4 border-b border-surface px-4 py-3';

  constructor() {
    const fonts = document.createElement('link');
    fonts.rel = 'stylesheet';
    fonts.href = FONTS;
    document.head.append(fonts);
  }

  protected use(look: (typeof LOOKS)[number]): void {
    this.look.set(look);
    usePreset(look.preset);
  }

  protected markNotSaved(): void {
    const [feature] = this.contents.featureRows;
    const [offering] = this.contents.offerings;
    this.matrix().notSaved(
      { featureId: feature.id, ...offering, availability: 'S' },
      'Not saved: the catalog was changed in another tab.',
    );
  }
}
