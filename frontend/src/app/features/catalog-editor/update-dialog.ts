import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { MessageService } from 'primeng/api';
import { Dialog } from 'primeng/dialog';
import { TableModule } from 'primeng/table';
import { Catalogs, CONFLICT_KIND_NAMES, ConflictKind, UpdatePreview } from '../../core/catalogs';
import { CatalogChangesList } from '../../shared/catalog-changes';
import { reasonOf } from '../../shared/reason-of';

/**
 * Where the owner of a stale working copy sees what an update from its lineage's current Approved
 * would do: what it brings into the catalog without asking, and the conflicts, each with how it
 * was before, how the catalog has it, and how the Approved version has it. It changes nothing.
 */
@Component({
  imports: [Dialog, TableModule, CatalogChangesList],
  selector: 'app-update-dialog',
  template: `
    <p-dialog
      header="Update from Approved"
      closeAriaLabel="Close"
      [modal]="true"
      [style]="{ width: '60rem' }"
      [(visible)]="visible"
    >
      @if (preview(); as update) {
        @let approved = 'Approved v' + update.approved.versionNumber;
        <div class="grid gap-6">
          <p data-update-summary>
            The update brings what {{ approved }} changed into this catalog, and keeps what you
            changed yourself.
          </p>
          <section aria-labelledby="update-taken">
            <h2 class="px-4 pb-2 font-semibold" id="update-taken">Taken from {{ approved }}</h2>
            <app-catalog-changes
              name="update"
              [changes]="update.taken"
              [none]="approved + ' brings nothing that this catalog does not have already.'"
            />
          </section>
          <section aria-labelledby="update-conflicts">
            <h2 class="px-4 pb-2 font-semibold" id="update-conflicts">Conflicts</h2>
            @if (update.conflicts.length === 0) {
              <p class="surface-empty" data-no-conflicts>
                There are no conflicts: nothing was changed differently in this catalog and in
                {{ approved }}.
              </p>
            } @else {
              <p-table size="small" [value]="update.conflicts">
                <ng-template #header>
                  <tr>
                    <th scope="col">Of</th>
                    <th scope="col">What</th>
                    <th scope="col">Before</th>
                    <th scope="col">In this catalog</th>
                    <th scope="col">In {{ approved }}</th>
                  </tr>
                </ng-template>
                <ng-template #body let-conflict>
                  <tr [attr.data-conflict]="conflict.id">
                    <td>{{ kindName(conflict.kind) }}</td>
                    <td>{{ conflict.what }}</td>
                    <td>{{ conflict.base }}</td>
                    <td>{{ conflict.mine }}</td>
                    <td>{{ conflict.theirs }}</td>
                  </tr>
                </ng-template>
              </p-table>
            }
          </section>
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

  /** Whether the dialog is open. */
  protected readonly visible = signal(false);

  /** The update as it was last worked out. */
  protected readonly preview = signal<UpdatePreview | null>(null);

  /** A conflict's kind as the table says it. A table's rows come to its template untyped. */
  protected kindName(kind: ConflictKind): string {
    return CONFLICT_KIND_NAMES[kind];
  }

  /**
   * Works the update out from the catalog as it is saved, and opens the dialog with it. When the
   * backend refuses, the dialog stays shut and the reason is shown as a message.
   */
  async open(): Promise<void> {
    try {
      this.preview.set(await this.catalogs.previewUpdate(this.catalogId()));
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
}
