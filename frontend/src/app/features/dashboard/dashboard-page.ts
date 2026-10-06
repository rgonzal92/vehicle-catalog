import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Catalogs, LineageSummary, STATUS_NAMES, WorkingCopy } from '../../core/catalogs';
import { Session } from '../../core/session';
import { NewCatalogDialog } from '../../shared/new-catalog-dialog/new-catalog-dialog';
import { reasonOf } from '../../shared/reason-of';

/** The first page a signed-in person sees, with a section for each thing their role can do. */
@Component({
  imports: [DatePipe, RouterLink, Button, Dialog, Message, TableModule, Tag, NewCatalogDialog],
  selector: 'app-dashboard-page',
  template: `
    <main class="mx-auto max-w-5xl px-6 py-10">
      <header class="flex items-center justify-between gap-4">
        <h1 class="text-2xl font-semibold">Dashboard</h1>
        <div class="flex items-center gap-4">
          <span>{{ session.person()?.name }}</span>
          <p-tag data-role severity="secondary" [value]="session.role() ?? undefined" />
          <p-button label="Sign out" severity="secondary" (onClick)="session.signOut()" />
        </div>
      </header>

      <section class="mt-10" aria-labelledby="my-catalogs">
        <div class="flex items-center justify-between gap-4">
          <h2 id="my-catalogs" class="text-xl font-semibold" tabindex="-1">My catalogs</h2>
          <p-button label="New catalog" (onClick)="newCatalog.open()" />
        </div>
        @if (mine()?.length) {
          <p-table class="mt-2 block" [value]="mine() ?? []">
            <ng-template #header>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Vehicle line</th>
                <th scope="col">Model year</th>
                <th scope="col">Status</th>
                <th scope="col">Last updated</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </ng-template>
            <ng-template #body let-catalog>
              <tr>
                <td>{{ catalog.name }}</td>
                <td>{{ catalog.vehicleLine }}</td>
                <td>{{ catalog.modelYear }}</td>
                <td>{{ statusNames[catalog.status] }}</td>
                <td>{{ catalog.updatedAt | date: 'medium' }}</td>
                <td class="text-right">
                  <a
                    class="text-primary underline"
                    [routerLink]="['/catalogs', catalog.id]"
                    [attr.aria-label]="'Open ' + catalog.name"
                  >
                    Open
                  </a>
                  @if (catalog.status === 'DRAFT') {
                    <p-button
                      class="ml-2"
                      label="Delete"
                      severity="secondary"
                      size="small"
                      [text]="true"
                      [ariaLabel]="'Delete ' + catalog.name"
                      [attr.data-delete]="catalog.id"
                      (onClick)="askToDelete(catalog)"
                    />
                  }
                </td>
              </tr>
            </ng-template>
          </p-table>
        } @else if (mine()) {
          <p class="mt-2 text-muted-color">You have no catalogs.</p>
        }
        <app-new-catalog-dialog #newCatalog />
        <p-dialog
          header="Delete working copy"
          closeAriaLabel="Close"
          [modal]="true"
          [style]="{ width: '30rem' }"
          [closable]="!deletingNow()"
          [visible]="deleting() !== null"
          (visibleChange)="deleting.set(null)"
          (onHide)="focusAfterQuestion()"
        >
          @if (deleting(); as asked) {
            <div class="grid gap-4">
              @if (deletionRefusal()) {
                <p-message severity="error">{{ deletionRefusal() }}</p-message>
              }
              <p data-question>
                Delete {{ asked.name }}? Its contents and its change history go with it, and it
                cannot be brought back.
              </p>
              <div class="flex justify-end gap-2">
                <p-button
                  label="Keep"
                  severity="secondary"
                  [autofocus]="true"
                  [disabled]="deletingNow()"
                  (onClick)="deleting.set(null)"
                />
                <p-button label="Delete" [loading]="deletingNow()" (onClick)="delete(asked)" />
              </div>
            </div>
          }
        </p-dialog>
      </section>

      <section class="mt-10" aria-labelledby="approved-catalogs">
        <h2 id="approved-catalogs" class="text-xl font-semibold">Approved catalogs</h2>
        @if (lineages()?.length) {
          <p-table class="mt-2 block" [value]="lineages() ?? []">
            <ng-template #header>
              <tr>
                <th scope="col">Vehicle line</th>
                <th scope="col">Model year</th>
                <th scope="col">Version</th>
                <th scope="col">Approved</th>
                <th scope="col">Approved by</th>
              </tr>
            </ng-template>
            <ng-template #body let-lineage>
              <tr>
                <td>
                  <a
                    class="text-primary underline"
                    [routerLink]="['/approved', lineage.id]"
                    [attr.aria-label]="'Open ' + lineage.vehicleLine + ' ' + lineage.modelYear"
                  >
                    {{ lineage.vehicleLine }}
                  </a>
                </td>
                <td>{{ lineage.modelYear }}</td>
                <td>{{ lineage.versionNumber }}</td>
                <td>{{ lineage.approvedAt | date: 'mediumDate' }}</td>
                <td>{{ lineage.approvedBy }}</td>
              </tr>
            </ng-template>
          </p-table>
        } @else if (lineages()) {
          <p class="mt-2 text-muted-color">There are no Approved catalogs.</p>
        }
      </section>

      @if (session.holds('manager')) {
        <section class="mt-10" aria-labelledby="review-queue">
          <h2 id="review-queue" class="text-xl font-semibold">Review queue</h2>
          <p class="mt-2 text-muted-color">Nothing is waiting for review.</p>
        </section>
      }

      @if (session.holds('admin')) {
        <section class="mt-10" aria-labelledby="admin-links">
          <h2 id="admin-links" class="text-xl font-semibold">Admin links</h2>
          <ul class="mt-2">
            <li>
              <a class="text-primary underline" routerLink="/admin/vehicle-lines">Vehicle lines</a>
            </li>
            <li>
              <a class="text-primary underline" routerLink="/admin/trims">Trims</a>
            </li>
            <li>
              <a class="text-primary underline" routerLink="/admin/regions">Regions</a>
            </li>
            <li>
              <a class="text-primary underline" routerLink="/admin/features">Feature library</a>
            </li>
          </ul>
        </section>
      }
    </main>
  `,
})
export class DashboardPage {
  protected readonly session = inject(Session);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly catalogs = inject(Catalogs);
  private readonly messages = inject(MessageService);

  /**
   * The lineages that have an Approved version, each with its current one, or null until the
   * backend has answered.
   */
  protected readonly lineages = signal<LineageSummary[] | null>(null);

  /** The person's working copies, or null until the backend has answered. */
  protected readonly mine = signal<WorkingCopy[] | null>(null);

  protected readonly statusNames: Record<string, string> = STATUS_NAMES;

  /** The working copy the person is asked to confirm the deletion of, or null while there is none. */
  protected readonly deleting = signal<WorkingCopy | null>(null);

  /** Why the backend refused to delete the working copy, shown with the question. */
  protected readonly deletionRefusal = signal('');

  /** Whether the deletion is on its way, so that a second click deletes nothing more. */
  protected readonly deletingNow = signal(false);

  /** The working copy the person was last asked about, which is where the focus goes back to. */
  private lastAsked?: WorkingCopy;

  constructor() {
    void this.readMine();
    void this.load(() => this.catalogs.lineages(), this.lineages);
  }

  /** Asks the person to confirm the deletion of one of their working copies. */
  protected askToDelete(catalog: WorkingCopy): void {
    this.deletionRefusal.set('');
    this.lastAsked = catalog;
    this.deleting.set(catalog);
  }

  /**
   * Puts the focus back once the question has gone from the page: on the working copy's delete
   * button when it still has one, or on the section's heading.
   */
  protected focusAfterQuestion(): void {
    const button = this.host.querySelector<HTMLElement>(
      `[data-delete="${this.lastAsked?.id}"] button`,
    );

    (button ?? this.host.querySelector<HTMLElement>('#my-catalogs'))?.focus();
  }

  /**
   * Deletes the working copy as the list shows it, and reads the list again. When the backend
   * refuses and the working copy can still be deleted, the question stays open with the reason,
   * about the working copy as the list shows it then. When it no longer can, the reason is shown as
   * a message.
   */
  protected async delete(catalog: WorkingCopy): Promise<void> {
    if (this.deletingNow()) {
      return;
    }

    this.deletingNow.set(true);
    let refusal = '';
    try {
      await this.catalogs.delete(catalog.id, catalog.revision);
    } catch (error) {
      // A revision conflict asks for a reload, which the list has had by the time this is read.
      refusal =
        error instanceof HttpErrorResponse && error.status === 412
          ? 'This working copy was changed after the list was read. The list shows it as it is now.'
          : reasonOf(error);
    }
    await this.readMine();
    const listed = this.mine()?.find(({ id }) => id === catalog.id);
    const deletable = !!refusal && listed?.status === 'DRAFT';
    if (refusal && !deletable) {
      this.messages.add({ severity: 'error', summary: 'Not deleted', detail: refusal });
    }
    this.deletionRefusal.set(refusal);
    this.deleting.set(deletable ? listed : null);
    this.deletingNow.set(false);
  }

  private readMine(): Promise<void> {
    return this.load(() => this.catalogs.mine(), this.mine);
  }

  private async load<T>(read: () => Promise<T>, into: { set(value: T): void }): Promise<void> {
    try {
      into.set(await read());
    } catch {
      // The failure has already been shown as a message.
    }
  }
}
