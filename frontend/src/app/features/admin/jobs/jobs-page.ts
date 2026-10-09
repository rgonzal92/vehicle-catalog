import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { reasonOf } from '../../../shared/reason-of';
import {
  Job,
  JOB_STATUS_NAMES,
  JOB_STATUS_SEVERITIES,
  JobPage,
  Jobs,
  JobStatus,
  subjectInWords,
  typeInWords,
} from './jobs';

/**
 * Where an admin sees the jobs the worker does: what each is, what it is about, how it stands, how
 * often it was tried, and what its last failure said. A job that has failed can be retried. The
 * list is read when it is shown, when it is paged or filtered, and when the admin asks for it
 * again: a job that is waiting shows as done once it has been read after the worker did it.
 */
@Component({
  imports: [DatePipe, ReactiveFormsModule, Button, Message, Select, TableModule, Tag],
  selector: 'app-jobs-page',
  template: `
    <div class="max-w-6xl">
      <div class="surface">
        <div class="surface-header">
          <div class="flex items-center gap-2">
            <label id="job-status-filter-label" for="job-status-filter">Status</label>
            <p-select
              inputId="job-status-filter"
              ariaLabelledBy="job-status-filter-label"
              optionLabel="name"
              optionValue="code"
              [formControl]="status"
              [options]="statusFilters"
              (onChange)="filter()"
            />
          </div>
          <p-button label="Refresh" severity="secondary" [loading]="loading()" (onClick)="load()" />
        </div>
        @if (failed()) {
          <p-message class="m-4 block" severity="error">
            <span>The jobs could not be read.</span>
            <p-button
              class="ml-4"
              label="Try again"
              severity="secondary"
              size="small"
              (onClick)="load()"
            />
          </p-message>
        }
        <p-table
          [value]="page()?.items ?? []"
          [loading]="loading()"
          [lazy]="true"
          [paginator]="true"
          [rows]="pageSize"
          [totalRecords]="page()?.total ?? 0"
          [(first)]="first"
          (onLazyLoad)="load()"
        >
          <ng-template #header>
            <tr>
              <th scope="col">Job</th>
              <th scope="col">About</th>
              <th scope="col">Status</th>
              <th scope="col">Attempts</th>
              <th scope="col">Last failure</th>
              <th scope="col">Queued</th>
              <th scope="col">Last changed</th>
              <th scope="col"><span class="sr-only">Actions</span></th>
            </tr>
          </ng-template>
          <ng-template #body let-job>
            <tr [attr.data-job]="job.id">
              <td>{{ typeInWords(job.type) }}</td>
              <td>{{ subjectInWords(job.subject) }}</td>
              <td>
                <p-tag [severity]="severityOf(job.status)" [value]="statusName(job.status)" />
              </td>
              <td>{{ job.attempts }}</td>
              <td class="max-w-md break-words">{{ job.error }}</td>
              <td>{{ job.createdAt | date: 'medium' }}</td>
              <td>{{ job.updatedAt | date: 'medium' }}</td>
              <td class="text-right whitespace-nowrap">
                @if (job.status === 'FAILED') {
                  <p-button
                    label="Retry"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [ariaLabel]="'Retry job ' + job.id"
                    [loading]="retrying() === job.id"
                    (onClick)="retry(job)"
                  />
                }
              </td>
            </tr>
          </ng-template>
          <ng-template #emptymessage>
            <tr>
              <td class="surface-empty" colspan="8">
                {{
                  page() ? (status.value ? 'No job has this status.' : 'There are no jobs.') : ''
                }}
              </td>
            </tr>
          </ng-template>
        </p-table>
      </div>
    </div>
  `,
})
export class JobsPage {
  private readonly jobs = inject(Jobs);
  private readonly messages = inject(MessageService);

  protected readonly typeInWords = typeInWords;
  protected readonly subjectInWords = subjectInWords;

  /** The status the listed jobs are to have, or none for every job. */
  protected readonly status = new FormControl<JobStatus | ''>('', { nonNullable: true });
  protected readonly statusFilters = [
    { code: '', name: 'Every status' },
    ...Object.entries(JOB_STATUS_NAMES).map(([code, name]) => ({ code, name })),
  ];

  protected readonly pageSize = 25;

  /** The position of the page's first job among all the jobs listed. */
  protected readonly first = signal(0);

  /** The page being shown, or null until the backend has answered. */
  protected readonly page = signal<JobPage | null>(null);

  /** Whether a page has been asked for and not yet answered. */
  protected readonly loading = signal(true);

  /** Whether the page last asked for could not be read. */
  protected readonly failed = signal(false);

  /** The job whose retry is on its way, or null while none is. */
  protected readonly retrying = signal<number | null>(null);

  /** How many pages have been asked for, so that a slow answer to an earlier one is dropped. */
  private asked = 0;

  /** A table's rows come to its template untyped. */
  protected statusName(status: JobStatus): string {
    return JOB_STATUS_NAMES[status];
  }

  protected severityOf(status: JobStatus): 'info' | 'success' | 'danger' {
    return JOB_STATUS_SEVERITIES[status];
  }

  /** Shows the first page of the jobs that have the chosen status. */
  protected filter(): void {
    this.first.set(0);
    void this.load();
  }

  /**
   * Reads the page the table is on. The table asks for this on its first display and on paging.
   * When the page cannot be read, the table keeps what it shows and the page says so.
   */
  protected async load(): Promise<void> {
    const mine = ++this.asked;
    this.loading.set(true);
    try {
      const page = await this.jobs.page(
        this.status.value,
        this.first() / this.pageSize,
        this.pageSize,
      );
      if (mine === this.asked) {
        this.page.set(page);
        this.failed.set(false);
      }
    } catch {
      if (mine === this.asked) {
        this.failed.set(true);
      }
    } finally {
      if (mine === this.asked) {
        this.loading.set(false);
      }
    }
  }

  /**
   * Has a failed job tried once more, and reads the list again either way: the job waits again,
   * or someone else has had it retried already.
   */
  protected async retry(job: Job): Promise<void> {
    if (this.retrying() !== null) {
      return;
    }
    this.retrying.set(job.id);
    try {
      await this.jobs.retry(job.id);
    } catch (error) {
      this.messages.add({
        severity: 'error',
        summary: 'The job was not retried',
        detail: reasonOf(error),
      });
    } finally {
      this.retrying.set(null);
    }
    await this.load();
  }
}
