import {
  afterNextRender,
  Component,
  computed,
  effect,
  inject,
  Injector,
  input,
  signal,
} from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Catalog, CatalogEdit, Catalogs } from '../../core/catalogs';
import { Library, LibraryRegion, LibraryTrim } from '../../core/library';
import { Cell, MatrixRegion, MatrixTrim } from '../../shared/availability-matrix/matrix';
import { reasonOf } from '../../shared/reason-of';

/** A number of things in words: "no cells", "1 cell", "120 cells". */
const counted = (count: number, thing: string) =>
  `${count === 0 ? 'no' : count} ${thing}${count === 1 ? '' : 's'}`;

/** The words with a capital first letter, to start a sentence with. */
const sentence = (words: string) => words.charAt(0).toUpperCase() + words.slice(1);

/** A removal waiting for the owner to confirm it, with what it would take along. */
interface Question {
  text: string;
  edit: CatalogEdit;
  /** The control that asked, which gets the focus back when the owner keeps what is there. */
  asker: HTMLElement | null;
}

/**
 * Where the owner of a working copy decides which library trims and regions the catalog has added
 * and where each trim is sold: every ticked box is an offering. Trims and regions come from the
 * library, with its names and its order; the catalog adds and removes them and never defines them.
 *
 * Each change is saved at once. Removing a trim, a region, or an offering first says how many
 * cells go with it.
 */
@Component({
  imports: [ReactiveFormsModule, Button, Dialog, Message, Select, TableModule, Tag],
  selector: 'app-manage-offerings-dialog',
  template: `
    <p-dialog
      header="Manage trims and regions"
      closeAriaLabel="Close"
      [modal]="true"
      [style]="{ width: '56rem' }"
      [(visible)]="visible"
    >
      <div class="grid gap-4">
        @if (refusal()) {
          <p-message severity="error">{{ refusal() }}</p-message>
        }
        @if (question(); as asked) {
          <div class="flex flex-wrap items-center justify-between gap-4" role="alert">
            <p data-question>{{ asked.text }}</p>
            <div class="flex gap-2">
              <p-button label="Keep" severity="secondary" [autofocus]="true" (onClick)="keep()" />
              <p-button label="Remove" (onClick)="apply(asked.edit)" />
            </div>
          </div>
        } @else {
          <p>Tick where each trim is sold. Each ticked box is an offering.</p>
        }
        <p-table size="small" [value]="catalog().snapshot.trims" [rowTrackBy]="trimIdentity">
          <ng-template #header>
            <tr>
              <th scope="col">Trim</th>
              @for (region of catalog().snapshot.regions; track region.code) {
                <th scope="col" class="text-center">
                  {{ region.name }}
                  @if (inactiveRegions().has(region.code)) {
                    <p-tag severity="secondary" value="Inactive" />
                  }
                  <p-button
                    label="Remove"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [disabled]="locked()"
                    [ariaLabel]="'Remove ' + region.name"
                    (onClick)="removeRegion(region, $event)"
                  />
                </th>
              }
              <th scope="col"><span class="sr-only">Actions</span></th>
            </tr>
          </ng-template>
          <ng-template #body let-trim>
            <tr>
              <th scope="row" class="font-normal">
                {{ trim.name }}
                @if (inactiveTrims().has(trim.id)) {
                  <p-tag severity="secondary" value="Inactive" />
                }
              </th>
              @for (region of catalog().snapshot.regions; track region.code) {
                <td class="text-center">
                  <input
                    type="checkbox"
                    class="size-4"
                    [checked]="sold().has(trim.id + ':' + region.code)"
                    [disabled]="locked()"
                    [attr.aria-label]="trim.name + ' is sold in ' + region.name"
                    (click)="toggle(trim, region, $event)"
                  />
                </td>
              }
              <td class="text-right">
                <p-button
                  label="Remove"
                  severity="secondary"
                  size="small"
                  [text]="true"
                  [disabled]="locked()"
                  [ariaLabel]="'Remove ' + trim.name"
                  (onClick)="removeTrim(trim, $event)"
                />
              </td>
            </tr>
          </ng-template>
          <ng-template #emptymessage>
            <tr>
              <td [attr.colspan]="catalog().snapshot.regions.length + 2">
                This catalog has no trims yet.
              </td>
            </tr>
          </ng-template>
        </p-table>
        @if (catalog().snapshot.regions.length === 0) {
          <p>This catalog has no regions yet.</p>
        }
        <div class="flex flex-wrap gap-4">
          <div class="grid gap-1">
            <label id="add-trim-label" for="add-trim">Add a trim</label>
            <p-select
              inputId="add-trim"
              ariaLabelledBy="add-trim-label"
              optionLabel="name"
              optionValue="id"
              appendTo="body"
              placeholder="Choose a trim"
              emptyMessage="The catalog has every active trim."
              [formControl]="trimToAdd"
              [options]="addableTrims()"
              (onChange)="addTrim($event.value)"
            />
          </div>
          <div class="grid gap-1">
            <label id="add-region-label" for="add-region">Add a region</label>
            <p-select
              inputId="add-region"
              ariaLabelledBy="add-region-label"
              optionLabel="name"
              optionValue="code"
              appendTo="body"
              placeholder="Choose a region"
              emptyMessage="The catalog has every active region."
              [formControl]="regionToAdd"
              [options]="addableRegions()"
              (onChange)="addRegion($event.value)"
            />
          </div>
        </div>
        <div class="flex justify-end">
          <p-button label="Done" (onClick)="visible.set(false)" />
        </div>
      </div>
    </p-dialog>
  `,
})
export class ManageOfferingsDialog {
  private readonly catalogs = inject(Catalogs);
  private readonly library = inject(Library);
  private readonly injector = inject(Injector);

  /** The working copy as it was last saved, which is what the cell counts are taken from. */
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

  /** Why the backend refused the last change, shown in the dialog. */
  protected readonly refusal = signal('');

  /** The removal the owner is being asked to confirm, or null while there is none. */
  protected readonly question = signal<Question | null>(null);

  /** Whether a change is on its way. */
  private readonly busy = signal(false);

  /** Whether no change is taken: one is on its way, or a removal is waiting to be confirmed. */
  protected readonly locked = computed(() => this.busy() || this.question() !== null);

  /** A trim's row stays the same row when the catalog is read again, so the focus stays in it. */
  protected readonly trimIdentity = (_: number, trim: MatrixTrim) => trim.id;

  protected readonly trimToAdd = new FormControl<number | null>(null);
  protected readonly regionToAdd = new FormControl<string | null>(null);

  private readonly libraryTrims = signal<LibraryTrim[]>([]);
  private readonly libraryRegions = signal<LibraryRegion[]>([]);

  /** The catalog's offerings, each as its trim's id and its region's code. */
  protected readonly sold = computed(
    () =>
      new Set(
        this.catalog().snapshot.offerings.map(
          ({ trimId, regionCode }) => `${trimId}:${regionCode}`,
        ),
      ),
  );

  /** The library's active trims that the catalog does not have yet. */
  protected readonly addableTrims = computed(() => {
    const has = new Set(this.catalog().snapshot.trims.map(({ id }) => id));
    return this.libraryTrims().filter((trim) => trim.active && !has.has(trim.id));
  });

  /** The library's active regions that the catalog does not have yet. */
  protected readonly addableRegions = computed(() => {
    const has = new Set(this.catalog().snapshot.regions.map(({ code }) => code));
    return this.libraryRegions().filter((region) => region.active && !has.has(region.code));
  });

  /** The trims the library has deactivated. One the catalog has stays until it is removed. */
  protected readonly inactiveTrims = computed(
    () =>
      new Set(
        this.libraryTrims()
          .filter(({ active }) => !active)
          .map(({ id }) => id),
      ),
  );

  /** The regions the library has deactivated. */
  protected readonly inactiveRegions = computed(
    () =>
      new Set(
        this.libraryRegions()
          .filter(({ active }) => !active)
          .map(({ code }) => code),
      ),
  );

  constructor() {
    effect(() => {
      if (!this.editable()) {
        this.visible.set(false);
      }
    });
    effect(() => {
      for (const adding of [this.trimToAdd, this.regionToAdd]) {
        if (this.locked()) {
          adding.disable();
        } else {
          adding.enable();
        }
      }
    });
  }

  /** Opens the dialog and reads the library's trims and regions, which it adds from. */
  async open(): Promise<void> {
    this.refusal.set('');
    this.question.set(null);
    this.visible.set(true);
    try {
      const [trims, regions] = await Promise.all([this.library.trims(), this.library.regions()]);
      this.libraryTrims.set(trims);
      this.libraryRegions.set(regions);
    } catch {
      // The failure has already been shown as a message.
    }
  }

  protected addTrim(trimId: number | null): void {
    this.trimToAdd.reset();
    if (trimId !== null) {
      void this.apply((revision) => this.catalogs.addTrims(this.id(), revision, [trimId]));
    }
  }

  protected addRegion(regionCode: string | null): void {
    this.regionToAdd.reset();
    if (regionCode !== null) {
      void this.apply((revision) => this.catalogs.addRegions(this.id(), revision, [regionCode]));
    }
  }

  /**
   * Ticks or unticks an offering. The box shows what is saved, so it is not left to change by
   * itself: ticking it is saved at once, and unticking it asks first.
   */
  protected toggle(trim: MatrixTrim, region: MatrixRegion, click: Event): void {
    click.preventDefault();
    const soldIn = this.catalog()
      .snapshot.offerings.filter(({ trimId }) => trimId === trim.id)
      .map(({ regionCode }) => regionCode);

    if (!soldIn.includes(region.code)) {
      void this.apply((revision) =>
        this.catalogs.sellIn(this.id(), revision, trim.id, [...soldIn, region.code]),
      );
      return;
    }
    const cells = this.cells((cell) => cell.trimId === trim.id && cell.regionCode === region.code);
    this.question.set({
      asker: click.target as HTMLElement,
      text:
        `${trim.name} will no longer be sold in ${region.name}. ` +
        `${sentence(counted(cells, 'cell'))} ${cells === 1 ? 'goes' : 'go'} with this offering.`,
      edit: (revision) =>
        this.catalogs.sellIn(
          this.id(),
          revision,
          trim.id,
          soldIn.filter((code) => code !== region.code),
        ),
    });
  }

  protected removeTrim(trim: MatrixTrim, click: Event): void {
    const { offerings } = this.catalog().snapshot;
    this.question.set({
      asker: (click.target as HTMLElement).closest('button'),
      text: this.removal(
        trim.name,
        offerings.filter(({ trimId }) => trimId === trim.id).length,
        this.cells((cell) => cell.trimId === trim.id),
      ),
      edit: (revision) => this.catalogs.removeTrim(this.id(), revision, trim.id),
    });
  }

  protected removeRegion(region: MatrixRegion, click: Event): void {
    const { offerings } = this.catalog().snapshot;
    this.question.set({
      asker: (click.target as HTMLElement).closest('button'),
      text: this.removal(
        region.name,
        offerings.filter(({ regionCode }) => regionCode === region.code).length,
        this.cells((cell) => cell.regionCode === region.code),
      ),
      edit: (revision) => this.catalogs.removeRegion(this.id(), revision, region.code),
    });
  }

  /** Leaves what the owner was asked about as it is, and puts the focus back where it was. */
  protected keep(): void {
    const asker = this.question()?.asker;
    this.question.set(null);
    // The control is disabled until the question has gone from the page.
    afterNextRender(() => asker?.focus(), { injector: this.injector });
  }

  /** Sends a change, and shows the reason when the backend refuses it. */
  protected async apply(edit: CatalogEdit): Promise<void> {
    this.refusal.set('');
    this.question.set(null);
    this.busy.set(true);
    try {
      await this.run()(edit);
    } catch (error) {
      this.refusal.set(reasonOf(error));
    } finally {
      this.busy.set(false);
    }
  }

  private id(): number {
    return this.catalog().snapshot.catalogId;
  }

  /** How many of the catalog's cells the test picks out. */
  private cells(test: (cell: Cell) => boolean): number {
    return this.catalog().snapshot.cells.filter(test).length;
  }

  /** What removing a trim or a region asks, with the offerings and cells that would go with it. */
  private removal(name: string, offerings: number, cells: number): string {
    return (
      `Remove ${name} from this catalog? ` +
      `${sentence(counted(offerings, 'offering'))} and ${counted(cells, 'cell')} go with it.`
    );
  }
}
