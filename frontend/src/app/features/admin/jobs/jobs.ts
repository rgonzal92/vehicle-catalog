import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** How a job stands: waiting or being tried, done, or failed for good. */
export type JobStatus = 'QUEUED' | 'SUCCEEDED' | 'FAILED';

/** A status as the page says it. */
export const JOB_STATUS_NAMES: Record<JobStatus, string> = {
  QUEUED: 'Queued',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
};

/** How the tag of each status is colored. */
export const JOB_STATUS_SEVERITIES: Record<JobStatus, 'info' | 'success' | 'danger'> = {
  QUEUED: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
};

/** Work that followed a change and was left to the worker. */
export interface Job {
  id: number;
  type: string;
  /** What it is about, such as the catalog. */
  subject: Record<string, unknown>;
  status: JobStatus;
  /** How often it has been tried. */
  attempts: number;
  /** What the last try that failed said, or null when the last try did not fail. */
  error: string | null;
  createdAt: string;
  updatedAt: string;
}

/** A page of jobs, newest first, and the number of jobs in all. */
export interface JobPage {
  items: Job[];
  total: number;
}

/** A job's type in words: `AFTER_APPROVAL` reads "After approval". */
export function typeInWords(type: string): string {
  const words = type.toLowerCase().replaceAll('_', ' ');

  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** What the names in a job's subject are called on screen. Any other name is shown as it is. */
const SUBJECT_NAMES: Record<string, string> = {
  catalogId: 'Catalog',
  documentId: 'Document',
  processing: 'reading',
  libraryRevision: 'library revision',
};

/** What a job is about, in words: "Catalog 41, library revision 7". */
export function subjectInWords(subject: Record<string, unknown>): string {
  return Object.entries(subject)
    .map(([name, value]) => `${SUBJECT_NAMES[name] ?? name} ${String(value)}`)
    .join(', ');
}

/** The jobs as an admin sees them. */
@Injectable({ providedIn: 'root' })
export class Jobs {
  private readonly http = inject(HttpClient);

  /** One page of the jobs, newest first, of one status if one is named. */
  page(status: JobStatus | '', page: number, size: number): Promise<JobPage> {
    return firstValueFrom(
      this.http.get<JobPage>('/api/jobs', {
        params: { ...(status ? { status } : {}), page, size },
      }),
    );
  }

  /** Has a failed job tried once more, and answers with the job as it then stands. */
  retry(id: number): Promise<Job> {
    return firstValueFrom(this.http.post<Job>(`/api/jobs/${id}/retry`, null));
  }
}
