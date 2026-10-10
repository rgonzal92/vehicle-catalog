import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Button, ButtonDirective, ButtonLabel } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { Textarea } from 'primeng/textarea';
import { baseInWords, Catalog, CatalogChanges, Catalogs, LONGEST_NOTE } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Issue, MatrixChanges } from '../../shared/availability-matrix/matrix';
import { CatalogChangesList } from '../../shared/catalog-changes';
import { SummaryPanel } from './summary-panel';
import { IssueCounts, IssueList } from '../../shared/issues';
import { Loading, ReadFailed } from '../../shared/read-state';
import { reasonOf } from '../../shared/reason-of';

/**
 * A Submitted catalog as a reviewer decides on it: whose it is and what its owner said, what it
 * changes against its base, its matrix with those changes marked, and its issues. A catalog made by
 * carryover shows what changed since the earlier model year's version, and one that started empty
 * shows everything as added. A reviewer who does not own the catalog approves it or rejects it
 * here.
 */
@Component({
  imports: [
    DatePipe,
    FormsModule,
    ReactiveFormsModule,
    RouterLink,
    Button,
    ButtonDirective,
    ButtonLabel,
    Dialog,
    Message,
    Textarea,
    AvailabilityMatrix,
    CatalogChangesList,
    SummaryPanel,
    IssueCounts,
    IssueList,
    Loading,
    ReadFailed,
  ],
  selector: 'app-review-page',
  template: `
    <div class="grid gap-6">
      @if (catalog(); as catalog) {
        <header class="grid gap-3">
          <h2 class="text-2xl font-semibold">{{ catalog.name }}</h2>
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
              <dt class="text-sm text-muted-color">Owner</dt>
              <dd>{{ catalog.owner }}</dd>
            </div>
            <div>
              <dt class="text-sm text-muted-color">Base</dt>
              <dd data-base>{{ baseInWords(catalog) }}</dd>
            </div>
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
          @if (catalog.snapshot.status === 'SUBMITTED') {
            @let blocked = approvalBlocked(catalog);
            <div class="flex flex-wrap items-center gap-3">
              <button
                pButton
                type="button"
                [disabled]="!!blocked"
                [attr.aria-describedby]="blocked ? 'approval-blocked' : null"
                (click)="ask('approve')"
              >
                <span pButtonLabel>Approve</span>
              </button>
              <button
                pButton
                type="button"
                severity="secondary"
                [disabled]="catalog.owned"
                [attr.aria-describedby]="catalog.owned ? 'approval-blocked' : null"
                (click)="ask('reject')"
              >
                <span pButtonLabel>Reject</span>
              </button>
              @if (blocked) {
                <span id="approval-blocked" class="text-sm text-muted-color">{{ blocked }}</span>
              }
            </div>
          }
        </header>

        <p-dialog
          closeAriaLabel="Close"
          [header]="deciding() === 'reject' ? 'Reject catalog' : 'Approve catalog'"
          [modal]="true"
          [style]="{ width: '32rem' }"
          [closable]="!decidingNow()"
          [visible]="deciding() !== null"
          (visibleChange)="deciding.set(null)"
        >
          <form class="grid gap-4" (ngSubmit)="decide(catalog)">
            @if (refusal()) {
              <p-message severity="error">{{ refusal() }}</p-message>
            }
            @if (deciding() === 'reject') {
              <p>
                The catalog goes back to {{ catalog.owner }} as a Draft, with what you say here.
              </p>
            } @else {
              <p>
                The catalog becomes the next Approved version of {{ catalog.vehicleLine }}
                {{ catalog.modelYear }}, and nobody can change it afterwards.
              </p>
            }
            <div class="grid gap-1">
              <label for="decision-comment">
                {{ deciding() === 'reject' ? 'Why it is rejected' : 'Comment (optional)' }}
              </label>
              <textarea
                pTextarea
                id="decision-comment"
                rows="4"
                [maxlength]="longestComment"
                [formControl]="comment"
              ></textarea>
            </div>
            <div class="flex justify-end gap-2">
              <p-button
                label="Cancel"
                severity="secondary"
                [disabled]="decidingNow()"
                (onClick)="deciding.set(null)"
              />
              <p-button
                type="submit"
                [label]="deciding() === 'reject' ? 'Reject' : 'Approve'"
                [disabled]="deciding() === 'reject' && !comment.value.trim()"
                [loading]="decidingNow()"
              />
            </div>
          </form>
        </p-dialog>

        @if (catalog.snapshot.status !== 'SUBMITTED') {
          <p class="notice" role="status" data-notice="nothing-to-review">
            This catalog is not waiting for review.
            <a
              class="font-medium text-primary hover:underline"
              [routerLink]="
                catalog.snapshot.status === 'APPROVED'
                  ? ['/approved', catalog.snapshot.lineageId]
                  : ['/catalogs', catalog.snapshot.catalogId]
              "
            >
              Open the catalog
            </a>
          </p>
        } @else {
          <div class="grid items-start gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
            <section class="surface" aria-labelledby="changes">
              <div class="surface-header">
                <h2 id="changes" class="font-semibold">What it changes</h2>
                <p class="text-sm text-muted-color">Against its base: {{ baseInWords(catalog) }}</p>
              </div>
              @if (changes(); as changed) {
                <app-catalog-changes
                  class="py-4"
                  name="review"
                  none="This catalog changes nothing against its base."
                  [changes]="changed"
                />
              }
            </section>
            <app-summary-panel [catalogId]="catalog.snapshot.catalogId" />
          </div>

          <section class="surface" aria-labelledby="matrix">
            <div class="surface-header">
              <h2 id="matrix" class="font-semibold">Features</h2>
            </div>
            <app-availability-matrix
              #matrix
              class="h-[70vh] min-h-96"
              [contents]="catalog.snapshot"
              [categories]="fixedLists.categories()"
              [issues]="issues()"
              [changes]="marks()"
            />
          </section>

          <section class="surface max-w-5xl" aria-labelledby="issues">
            <div class="surface-header">
              <h2 id="issues" class="font-semibold">Issues</h2>
              <app-issue-counts
                data-issue-counts
                [errors]="errors()"
                [warnings]="issues().length - errors()"
              />
            </div>
            <app-issue-list
              [issues]="issues()"
              [contents]="catalog.snapshot"
              (showCell)="showCell($event, matrix)"
            />
          </section>
        }
      } @else if (missing()) {
        <p>There is no catalog to review at this address.</p>
      } @else if (failed()) {
        <app-read-failed what="The catalog could not be read." (again)="open()" />
      } @else {
        <app-loading class="surface" />
      }
    </div>
  `,
})
export class ReviewPage {
  private readonly catalogs = inject(Catalogs);
  private readonly messages = inject(MessageService);
  private readonly router = inject(Router);
  protected readonly fixedLists = inject(FixedLists);
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  /** The catalog under review, once it has loaded. */
  protected readonly catalog = signal<Catalog | null>(null);

  /** What the catalog changes against its base, once that has loaded. */
  protected readonly changes = signal<CatalogChanges | null>(null);

  /** Whether the address names no catalog, or one this person may not open. */
  protected readonly missing = signal(false);

  /** Whether the page could not be read, which it then says. */
  protected readonly failed = signal(false);

  protected readonly baseInWords = baseInWords;

  /** The decision the reviewer is making in the dialog, or null while they make none. */
  protected readonly deciding = signal<'approve' | 'reject' | null>(null);

  /** What the reviewer says with the decision. A rejection needs it. */
  protected readonly comment = new FormControl('', { nonNullable: true });
  protected readonly longestComment = LONGEST_NOTE;

  /** Why the backend refused the decision, shown in the dialog. */
  protected readonly refusal = signal('');

  /** Whether the decision is on its way, so that a second click decides nothing more. */
  protected readonly decidingNow = signal(false);

  protected readonly issues = computed(() => this.catalogs.issuesOf(this.id));
  protected readonly errors = computed(
    () => this.issues().filter(({ severity }) => severity === 'ERROR').length,
  );

  /** The changes as the matrix marks them: what each changed cell was, and what was added. */
  protected readonly marks = computed<MatrixChanges | null>(() => {
    const changes = this.changes();
    return (
      changes && {
        before: new Map(
          changes.cellsChanged.map((cell) => [
            `${cell.featureId}:${cell.trimId}:${cell.regionCode}`,
            cell.before,
          ]),
        ),
        addedFeatures: new Set(changes.featureRowsAdded.map(({ id }) => id)),
        addedOfferings: new Set(
          changes.offeringsAdded.map(({ trimId, regionCode }) => `${trimId}:${regionCode}`),
        ),
      }
    );
  });

  constructor() {
    void this.fixedLists.load();
    void this.open();
  }

  /**
   * Why the reviewer cannot approve the catalog as it is, which the page says beside the buttons,
   * or nothing when they can. Nobody decides on a catalog of their own.
   */
  protected approvalBlocked(catalog: Catalog): string {
    if (catalog.owned) {
      return 'This catalog is yours, and nobody decides on their own.';
    }
    if (catalog.stale) {
      return 'It is stale: another version of its lineage was approved after it was made.';
    }
    if (catalog.vehicleLineActive === false) {
      return 'Its vehicle line is deactivated.';
    }
    const errors = this.errors();
    return errors === 0
      ? ''
      : `It has ${errors === 1 ? '1 Error' : `${errors} Errors`}, so it cannot be approved.`;
  }

  protected ask(decision: 'approve' | 'reject'): void {
    this.comment.reset();
    this.refusal.set('');
    this.deciding.set(decision);
  }

  /**
   * Sends the decision about the catalog as the page shows it, and goes back to the dashboard once
   * it is made. When the backend refuses, the dialog stays open with the reason; when the catalog
   * has changed since the page read it, the page reads it again.
   */
  protected async decide(catalog: Catalog): Promise<void> {
    const decision = this.deciding();
    const comment = this.comment.value.trim();
    if (!decision || this.decidingNow() || (decision === 'reject' && !comment)) {
      return;
    }

    this.refusal.set('');
    this.decidingNow.set(true);
    try {
      const { catalogId, revision } = catalog.snapshot;
      await (decision === 'approve'
        ? this.catalogs.approve(catalogId, revision, comment)
        : this.catalogs.reject(catalogId, revision, comment));
      this.messages.add({
        severity: 'success',
        summary: decision === 'approve' ? 'Approved' : 'Rejected',
        detail:
          decision === 'approve'
            ? `${catalog.name} is now an Approved version.`
            : `${catalog.name} is back with ${catalog.owner}.`,
      });
      await this.router.navigateByUrl('/dashboard');
    } catch (error) {
      if (error instanceof HttpErrorResponse && [404, 412].includes(error.status)) {
        // The catalog is no longer as the page shows it, so the page shows it as it is now.
        this.deciding.set(null);
        this.messages.add({
          severity: 'warn',
          summary: 'Not decided',
          detail: 'This catalog changed after you opened it. It is shown as it is now.',
        });
        await this.open();
      } else {
        this.refusal.set(reasonOf(error));
      }
    } finally {
      this.decidingNow.set(false);
    }
  }

  /** Brings the cell an issue is about into view in the matrix. */
  protected showCell(issue: Issue, matrix: AvailabilityMatrix): void {
    const { featureId, trimId, regionCode } = issue;
    if (featureId !== null && trimId !== null && regionCode !== null) {
      matrix.show({ featureId, trimId, regionCode });
    }
  }

  /** Reads the catalog and what it changes against its base. */
  protected async open(): Promise<void> {
    this.failed.set(false);
    try {
      const [catalog, changes] = await Promise.all([
        this.catalogs.find(this.id),
        this.catalogs.diff(this.id, 'base'),
      ]);
      this.catalog.set(catalog);
      this.changes.set(changes);
    } catch (error) {
      // The backend refuses an address that names no catalog the person may open. Any other failure
      // has been shown as a message.
      this.missing.set(error instanceof HttpErrorResponse && [400, 404].includes(error.status));
      this.failed.set(!this.missing());
    }
  }
}
