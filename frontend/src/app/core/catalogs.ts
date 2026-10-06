import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatrixContents } from '../shared/availability-matrix/matrix';

/** A lineage with its current Approved version, as the dashboard lists it. */
export interface LineageSummary {
  id: number;
  vehicleLine: string;
  modelYear: number;
  /** The catalog that is the current Approved version. */
  catalogId: number;
  versionNumber: number;
  approvedBy: string;
  approvedAt: string;
}

/** One Approved version in a lineage's list of versions. */
export interface VersionSummary {
  catalogId: number;
  versionNumber: number;
  name: string;
  approvedBy: string;
  approvedAt: string;
}

/** Whether a catalog is a working copy, in Draft or Submitted, or an Approved version. */
export type CatalogStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED';

/** The name shown for each status. */
export const STATUS_NAMES: Record<CatalogStatus, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  APPROVED: 'Approved',
};

/** A catalog: what describes it, and its contents as the matrix shows them. */
export interface Catalog {
  name: string;
  /** Its number among its lineage's Approved versions, or null for a working copy. */
  versionNumber: number | null;
  vehicleLineId: number;
  vehicleLine: string;
  modelYear: number;
  approvedBy: string | null;
  approvedAt: string | null;
  /**
   * The Approved version it was copied from, or null when it started empty. After a carryover its
   * model year is an earlier one than the catalog's.
   */
  base: { catalogId: number; modelYear: number; versionNumber: number } | null;
  snapshot: MatrixContents & {
    catalogId: number;
    lineageId: number;
    status: CatalogStatus;
    revision: number;
  };
}

/** A working copy as its owner's list shows it. */
export interface WorkingCopy {
  id: number;
  name: string;
  vehicleLine: string;
  modelYear: number;
  status: CatalogStatus;
  updatedAt: string;
}

/** What a person gives to create a working copy. */
export type NewWorkingCopy = Pick<WorkingCopy, 'name' | 'modelYear'> & { vehicleLineId: number };

/**
 * What a new working copy would start from: its lineage's current Approved version (a copy), an
 * earlier model year's (a carryover), or nothing.
 */
export type StartPoint =
  | { kind: 'COPY' | 'CARRYOVER'; modelYear: number; versionNumber: number }
  | { kind: 'EMPTY' };

/** A starting point in the words the new catalog dialog shows. */
export function startPointInWords(start: StartPoint): string {
  switch (start.kind) {
    case 'COPY':
      return `Starts from Approved v${start.versionNumber}`;
    case 'CARRYOVER':
      return `Starts from ${start.modelYear} Approved v${start.versionNumber} (carryover)`;
    case 'EMPTY':
      return 'Starts empty';
  }
}

/** Reads lineages, their Approved versions, and whole catalogs, and creates working copies. */
@Injectable({ providedIn: 'root' })
export class Catalogs {
  private readonly http = inject(HttpClient);

  /** Every lineage that has an Approved version, with its current one. */
  lineages(): Promise<LineageSummary[]> {
    return firstValueFrom(this.http.get<LineageSummary[]>('/api/lineages'));
  }

  /** The Approved versions of a lineage, newest first. */
  versions(lineageId: number): Promise<VersionSummary[]> {
    return firstValueFrom(this.http.get<VersionSummary[]>(`/api/lineages/${lineageId}/versions`));
  }

  find(catalogId: number): Promise<Catalog> {
    return firstValueFrom(this.http.get<Catalog>(`/api/catalogs/${catalogId}`));
  }

  /** The signed-in person's working copies, the one changed last first. */
  mine(): Promise<WorkingCopy[]> {
    return firstValueFrom(
      this.http.get<WorkingCopy[]>('/api/catalogs', { params: { scope: 'mine' } }),
    );
  }

  /** What a new working copy for the vehicle line and model year would start from. */
  startPoint(vehicleLineId: number, modelYear: number): Promise<StartPoint> {
    return firstValueFrom(
      this.http.get<StartPoint>('/api/catalogs/start-point', {
        params: { vehicleLineId, modelYear },
      }),
    );
  }

  /** Creates a working copy that belongs to the signed-in person. */
  create(given: NewWorkingCopy): Promise<WorkingCopy> {
    return firstValueFrom(this.http.post<WorkingCopy>('/api/catalogs', given));
  }
}
