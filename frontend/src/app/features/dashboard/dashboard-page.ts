import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Plus } from '@primeicons/angular/plus';
import { Button, ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import {
  Catalogs,
  LineageSummary,
  STATUS_NAMES,
  STATUS_SEVERITIES,
  WorkingCopy,
} from '../../core/catalogs';
import { Session } from '../../core/session';
import { NewCatalogDialog } from '../../shared/new-catalog-dialog/new-catalog-dialog';
import { IssueCounts } from '../../shared/issues';
import { Loading, ReadFailed } from '../../shared/read-state';
import { reasonOf } from '../../shared/reason-of';

/** The first page a signed-in person sees, with a section for each thing their role can do. */
@Component({
  imports: [
    DatePipe,
    RouterLink,
    Plus,
    Button,
    ButtonDirective,
    ButtonIcon,
    ButtonLabel,
    Dialog,
    Message,
    TableModule,
    Tag,
    NewCatalogDialog,
    IssueCounts,
    Loading,
    ReadFailed,
  ],
  selector: 'app-dashboard-page',
  template: `
    <div class="grid max-w-6xl gap-6">
      <section class="surface" aria-labelledby="my-catalogs">
        <div class="surface-header">
          <h2 id="my-catalogs" class="font-semibold" tabindex="-1">My catalogs</h2>
          <button pButton type="button" (click)="newCatalog.open()">
            <svg data-p-icon="plus" pButtonIcon />
            <span pButtonLabel>New catalog</span>
          </button>
        </div>
        @if (mine()?.length) {
          <p-table [value]="mine() ?? []">
            <ng-template #header>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Vehicle line</th>
                <th scope="col">Model year</th>
                <th scope="col">Status</th>
                <th scope="col">Issues</th>
                <th scope="col">Last updated</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </ng-template>
            <ng-template #body let-catalog>
              <tr>
                <td>{{ catalog.name }}</td>
                <td>{{ catalog.vehicleLine }}</td>
                <td>{{ catalog.modelYear }}</td>
                <td>
                  <p-tag
                    [severity]="statusSeverities[catalog.status]"
                    [value]="statusNames[catalog.status]"
                  />
                </td>
                <td>
                  <!-- A backend that is one release behind counts no issues. -->
                  @if (catalog.errors !== undefined) {
                    <app-issue-counts [errors]="catalog.errors" [warnings]="catalog.warnings" />
                  }
                </td>
                <td>{{ catalog.updatedAt | date: 'medium' }}</td>
                <td class="text-right whitespace-nowrap">
                  <a
                    class="font-medium text-primary hover:underline"
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
          <p class="surface-empty">You have no catalogs.</p>
        } @else if (mineFailed()) {
          <app-read-failed
            class="m-4"
            what="Your catalogs could not be read."
            (again)="readMine()"
          />
        } @else {
          <app-loading />
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

      <section class="surface" aria-labelledby="approved-catalogs">
        <div class="surface-header">
          <h2 id="approved-catalogs" class="font-semibold">Approved catalogs</h2>
        </div>
        @if (lineages()?.length) {
          <p-table [value]="lineages() ?? []">
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
                    class="font-medium text-primary hover:underline"
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
          <p class="surface-empty">There are no Approved catalogs.</p>
        } @else if (lineagesFailed()) {
          <app-read-failed
            class="m-4"
            what="The Approved catalogs could not be read."
            (again)="readLineages()"
          />
        } @else {
          <app-loading />
        }
      </section>

      @if (session.holds('manager')) {
        <section class="surface" aria-labelledby="review-queue">
          <div class="surface-header">
            <h2 id="review-queue" class="font-semibold">Review queue</h2>
          </div>
          <p class="surface-empty">Nothing is waiting for review.</p>
        </section>
      }
    </div>
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

  /** Whether the last reading of each list failed, which the list then says. */
  protected readonly mineFailed = signal(false);
  protected readonly lineagesFailed = signal(false);

  protected readonly statusNames: Record<string, string> = STATUS_NAMES;
  protected readonly statusSeverities: Record<string, 'secondary' | 'info' | 'success'> =
    STATUS_SEVERITIES;

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
    void this.readLineages();
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

  protected readMine(): Promise<void> {
    return this.load(() => this.catalogs.mine(), this.mine, this.mineFailed);
  }

  protected readLineages(): Promise<void> {
    return this.load(() => this.catalogs.lineages(), this.lineages, this.lineagesFailed);
  }

  private async load<T>(
    read: () => Promise<T>,
    into: { set(value: T): void },
    failed: { set(value: boolean): void },
  ): Promise<void> {
    failed.set(false);
    try {
      into.set(await read());
    } catch {
      // The failure has been shown as a message too, which goes away; the list goes on saying so.
      failed.set(true);
    }
  }
}
