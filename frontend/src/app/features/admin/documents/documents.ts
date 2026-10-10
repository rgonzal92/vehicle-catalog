import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

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

  /** Every document, newest first. */
  list(): Promise<UploadedDocument[]> {
    return firstValueFrom(this.http.get<UploadedDocument[]>('/api/documents'));
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

  async delete(id: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/documents/${id}`));
  }
}
