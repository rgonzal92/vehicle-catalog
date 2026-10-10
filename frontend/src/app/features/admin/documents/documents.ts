import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { IN_THE_BACKGROUND } from '../../../core/api-error-interceptor';

/** How far a document is: waiting to be read, being read, ready to be searched, or failed. */
export type DocumentStatus = 'WAITING' | 'RUNNING' | 'READY' | 'FAILED';

/** A note an admin uploaded about one vehicle line's model year, as the list shows it. */
export interface UploadedDocument {
  id: number;
  title: string;
  vehicleLineId: number;
  vehicleLine: string;
  modelYear: number;
  fileName: string;
  sizeBytes: number;
  uploadedBy: string;
  uploadedAt: string;
  status: DocumentStatus;
  /** Why it failed, for one that did. */
  reason: string | null;
  /** How many passages it was split into, which a ready one is searched by. */
  passages: number;
}

/** The largest file a document may be, which the backend holds an upload to as well. */
export const LARGEST_DOCUMENT_BYTES = 2 * 1024 * 1024;

export const LONGEST_DOCUMENT_TITLE = 80;

export const DOCUMENT_STATUS_NAMES: Record<DocumentStatus, string> = {
  WAITING: 'Waiting',
  RUNNING: 'Running',
  READY: 'Ready',
  FAILED: 'Failed',
};

/** The documents admins upload. A refused request is the caller's to show, beside what was asked. */
@Injectable({ providedIn: 'root' })
export class Documents {
  private readonly http = inject(HttpClient);

  /**
   * Every document, newest first.
   *
   * @param unasked whether nobody is waiting for it, as when the page looks again by itself: a
   *     failure is then shown to no one
   */
  list(unasked = false): Promise<UploadedDocument[]> {
    return firstValueFrom(
      this.http.get<UploadedDocument[]>('/api/documents', {
        context: new HttpContext().set(IN_THE_BACKGROUND, unasked),
      }),
    );
  }

  /** Uploads one document, as a form with its file. */
  upload(
    file: File,
    title: string,
    vehicleLineId: number,
    modelYear: number,
  ): Promise<UploadedDocument> {
    const form = new FormData();
    form.set('file', file);
    form.set('title', title);
    form.set('vehicleLineId', String(vehicleLineId));
    form.set('modelYear', String(modelYear));

    return firstValueFrom(this.http.post<UploadedDocument>('/api/documents', form));
  }

  /** Gives a document that has failed to the worker once more. */
  processAgain(id: number): Promise<UploadedDocument> {
    return firstValueFrom(this.http.post<UploadedDocument>(`/api/documents/${id}/process`, null));
  }

  async delete(id: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/documents/${id}`));
  }
}
