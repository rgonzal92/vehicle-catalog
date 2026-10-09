import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { TableModule } from 'primeng/table';
import {
  CatalogEdit,
  Catalogs,
  CONFLICT_KIND_NAMES,
  ConflictKind,
  ConflictSide,
  UpdatePreview,
} from '../../core/catalogs';
import { CatalogChangesList } from '../../shared/catalog-changes';
import { reasonOf } from '../../shared/reason-of';
import { failureOf } from './save-queue';

/**
 * Where the owner of a stale working copy updates it from its lineage's current Approved. It shows
 * what the update brings into the catalog without asking, and the conflicts, each with how it was
 * before, how the catalog has it, and how the Approved version has it. The owner takes one side of
 * every conflict, and only then can the catalog be updated.
 */
@Component({
  imports: [Button, Dialog, Message, TableModule, CatalogChangesList],
  selector: 'app-update-dialog',
  template: `
    <p-dialog
      header="Update from Approved"
      closeAriaLabel="Close"
      [modal]="true"
      [style]="{ width: '68rem' }"
      [closable]="!updating()"
      [(visible)]="visible"
    >
      @if (preview(); as plan) {
        @let approved = 'Approved v' + plan.approved.versionNumber;
        <div class="grid gap-6">
          <p data-update-summary>
            The update brings what {{ approved }} changed into this catalog, and keeps what you
            changed yourself.
          </p>
          <section aria-labelledby="update-taken">
            <h2 class="px-4 pb-2 font-semibold" id="update-taken">Taken from {{ approved }}</h2>
            <app-catalog-changes
              name="update"
              [changes]="plan.taken"
              [none]="approved + ' brings nothing that this catalog does not have already.'"
            />
          </section>
          <section aria-labelledby="update-conflicts">
            <h2 class="px-4 pb-2 font-semibold" id="update-conflicts">Conflicts</h2>
            @if (plan.conflicts.length === 0) {
              <p class="surface-empty" data-no-conflicts>
                There are no conflicts: nothing was changed differently in this catalog and in
                {{ approved }}.
              </p>
            } @else {
              <p-table size="small" [value]="plan.conflicts">
                <ng-template #header>
                  <tr>
                    <th scope="col">Of</th>
                    <th scope="col">What</th>
                    <th scope="col">Before</th>
                    <th scope="col">In this catalog</th>
                    <th scope="col">In {{ approved }}</th>
                    <th scope="col">Take</th>
                  </tr>
                </ng-template>
                <ng-template #body let-conflict>
                  <tr [attr.data-conflict]="conflict.id">
                    <td>{{ kindName(conflict.kind) }}</td>
                    <td>{{ conflict.what }}</td>
                    <td>{{ conflict.base }}</td>
                    <td>{{ conflict.mine }}</td>
                    <td>{{ conflict.theirs }}</td>
                    <td>
                      <div
                        class="flex flex-wrap gap-x-4 gap-y-1"
                        role="radiogroup"
                        [attr.aria-label]="'Take for ' + conflict.what"
                      >
                        @for (side of sides; track side.side) {
                          <label class="flex items-center gap-2 whitespace-nowrap">
                            <input
                              class="accent-primary"
                              type="radio"
                              [name]="conflict.id"
                              [checked]="choices().get(conflict.id) === side.side"
                              [disabled]="updating()"
                              (change)="choose(conflict.id, side.side)"
                            />
                            {{ side.side === 'MINE' ? 'Mine' : approved }}
                          </label>
                        }
                      </div>
                    </td>
                  </tr>
                </ng-template>
              </p-table>
            }
          </section>
          @if (refusal()) {
            <p-message severity="error">
              <div class="flex flex-wrap items-center gap-3">
                <span>{{ refusal() }}</span>
                <p-button
                  label="Work the update out again"
                  severity="secondary"
                  size="small"
                  (onClick)="open()"
                />
              </div>
            </p-message>
          }
          <div class="flex flex-wrap items-center justify-end gap-3">
            @if (!settled()) {
              <span class="text-muted-color" id="update-blocked">
                Take one side of every conflict first.
              </span>
            }
            <p-button
              label="Cancel"
              severity="secondary"
              [disabled]="updating()"
              (onClick)="visible.set(false)"
            />
            <p-button
              label="Update catalog"
              [disabled]="!settled()"
              [loading]="updating()"
              [attr.aria-describedby]="settled() ? null : 'update-blocked'"
              (onClick)="update(plan)"
            />
          </div>
        </div>
      }
    </p-dialog>
  `,
})
export class UpdateDialog {
  private readonly catalogs = inject(Catalogs);
  private readonly messages = inject(MessageService);

  /** The stale working copy. */
  readonly catalogId = input.required<number>();

  /**
   * Sends an edit behind the saves on their way and reads the catalog again once it is saved. It
   * fails with the backend's refusal when the edit is not saved.
   */
  readonly run = input.required<(edit: CatalogEdit) => Promise<void>>();

  /** Whether the dialog is open. */
  protected readonly visible = signal(false);

  /** The update as it was last worked out. */
  protected readonly preview = signal<UpdatePreview | null>(null);

  /** The side the owner takes of each conflict they have settled, by the conflict's id. */
  protected readonly choices = signal<ReadonlyMap<string, ConflictSide>>(new Map());

  /** Whether every conflict has a side taken, so that the catalog can be updated. */
  protected readonly settled = computed(
    () => this.preview()?.conflicts.every(({ id }) => this.choices().has(id)) ?? false,
  );

  /** Whether the update is on its way, so that a second click updates nothing more. */
  protected readonly updating = signal(false);

  /** Why the backend refused the update, shown in the dialog. */
  protected readonly refusal = signal('');

  protected readonly sides: { side: ConflictSide }[] = [{ side: 'MINE' }, { side: 'THEIRS' }];

  /** A conflict's kind as the table says it. A table's rows come to its template untyped. */
  protected kindName(kind: ConflictKind): string {
    return CONFLICT_KIND_NAMES[kind];
  }

  /**
   * Works the update out from the catalog as it is saved, and opens the dialog with it and no side
   * taken yet. When the backend refuses, the dialog stays as it is and the reason is shown as a
   * message.
   */
  async open(): Promise<void> {
    try {
      this.preview.set(await this.catalogs.previewUpdate(this.catalogId()));
      this.choices.set(new Map());
      this.refusal.set('');
      this.visible.set(true);
    } catch (error) {
      // A server or network failure has already been shown as a message.
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) {
        this.messages.add({
          severity: 'error',
          summary: 'No update from Approved',
          detail: reasonOf(error),
        });
      }
    }
  }

  protected choose(conflictId: string, side: ConflictSide): void {
    this.choices.update((taken) => new Map(taken).set(conflictId, side));
  }

  /**
   * Updates the catalog as it was when the update was worked out, with the sides taken. When the
   * backend refuses because the Approved version has moved on, the dialog stays open with the
   * reason and offers to work the update out again.
   */
  protected async update(update: UpdatePreview): Promise<void> {
    if (this.updating()) {
      return;
    }

    this.refusal.set('');
    this.updating.set(true);
    try {
      await this.run()(() =>
        this.catalogs.update(
          this.catalogId(),
          update.revision,
          update.approved.catalogId,
          Object.fromEntries(this.choices()),
        ),
      );
      this.visible.set(false);
      this.messages.add({
        severity: 'success',
        summary: 'Catalog updated',
        detail: `It now has Approved v${update.approved.versionNumber} as its base.`,
      });
    } catch (error) {
      if (failureOf(error) === 'rejected') {
        this.refusal.set(reasonOf(error));
      } else {
        // The editor has stopped or read the catalog again, and says so itself.
        this.visible.set(false);
      }
    } finally {
      this.updating.set(false);
    }
  }
}
