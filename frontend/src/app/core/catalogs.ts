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

/** A catalog: what describes it, and its contents as the matrix shows them. */
export interface Catalog {
  name: string;
  /** Its number among its lineage's Approved versions, or null for a working copy. */
  versionNumber: number | null;
  vehicleLine: string;
  modelYear: number;
  approvedBy: string | null;
  approvedAt: string | null;
  snapshot: MatrixContents & {
    catalogId: number;
    lineageId: number;
    status: 'DRAFT' | 'SUBMITTED' | 'APPROVED';
    revision: number;
  };
}

/** Reads lineages, their Approved versions, and whole catalogs from the backend. */
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
}
