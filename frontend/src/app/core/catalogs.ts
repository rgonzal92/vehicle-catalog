import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { Cell, MatrixContents } from '../shared/availability-matrix/matrix';

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
  /** Whether the signed-in person owns it, which lets them edit it while it is in status Draft. */
  owned: boolean;
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

/**
 * One change to a catalog, as its change history lists it: who made it, when, its kind, and what it
 * touched. A change names only what its kind is about, and the rest is null.
 */
export interface Change {
  id: number;
  at: string;
  /** The name of the person who made the change. */
  actor: string;
  /** Such as `CELL_SET`. New kinds appear without the frontend knowing them. */
  kind: string;
  featureCode: string | null;
  featureName: string | null;
  trim: string | null;
  region: string | null;
  /** What a cell was before the change, and what it was set to: S, A, or N. */
  oldValue: string | null;
  newValue: string | null;
}

/** One page of a catalog's changes, newest first, and how many there are in all. */
export interface ChangePage {
  items: Change[];
  total: number;
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

/**
 * An edit of a working copy that is ready to be sent: given the revision it is made from, it sends
 * itself and answers with the revision it led to.
 */
export type CatalogEdit = (revision: number) => Promise<number>;

/** How long, in milliseconds, an edit may go unanswered before its outcome counts as unknown. */
export const SAVE_PATIENCE = 20_000;

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

/**
 * Reads lineages, their Approved versions, whole catalogs, and their change history, and creates
 * and edits working copies.
 */
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

  /** One page of the catalog's change history, newest first. Pages are numbered from 0. */
  changes(catalogId: number, page: number, size: number): Promise<ChangePage> {
    return firstValueFrom(
      this.http.get<ChangePage>(`/api/catalogs/${catalogId}/changes`, { params: { page, size } }),
    );
  }

  /** Sets cells of a working copy. Setting a cell to Not offered removes it. */
  setCells(catalogId: number, revision: number, cells: Cell[]): Promise<number> {
    return this.edit('PUT', `/api/catalogs/${catalogId}/cells`, revision, cells);
  }

  /** Adds library trims to a working copy. A new trim is sold nowhere until its regions are set. */
  addTrims(catalogId: number, revision: number, trimIds: number[]): Promise<number> {
    return this.edit('POST', `/api/catalogs/${catalogId}/trims`, revision, { trimIds });
  }

  /** Removes a trim from a working copy, and with it its offerings and their cells. */
  removeTrim(catalogId: number, revision: number, trimId: number): Promise<number> {
    return this.edit('DELETE', `/api/catalogs/${catalogId}/trims/${trimId}`, revision);
  }

  /** Adds library regions to a working copy. */
  addRegions(catalogId: number, revision: number, regionCodes: string[]): Promise<number> {
    return this.edit('POST', `/api/catalogs/${catalogId}/regions`, revision, { regionCodes });
  }

  /** Removes a region from a working copy, and with it its offerings and their cells. */
  removeRegion(catalogId: number, revision: number, regionCode: string): Promise<number> {
    return this.edit('DELETE', `/api/catalogs/${catalogId}/regions/${regionCode}`, revision);
  }

  /** Adds library features to a working copy as feature rows, each with every cell Not offered. */
  addFeatures(catalogId: number, revision: number, featureIds: number[]): Promise<number> {
    return this.edit('POST', `/api/catalogs/${catalogId}/features`, revision, { featureIds });
  }

  /** Removes a feature row from a working copy, and with it its cells. */
  removeFeature(catalogId: number, revision: number, featureId: number): Promise<number> {
    return this.edit('DELETE', `/api/catalogs/${catalogId}/features/${featureId}`, revision);
  }

  /** Says in which of a working copy's regions a trim is sold: in exactly the ones given. */
  sellIn(
    catalogId: number,
    revision: number,
    trimId: number,
    regionCodes: string[],
  ): Promise<number> {
    return this.edit('PUT', `/api/catalogs/${catalogId}/trims/${trimId}/regions`, revision, {
      regionCodes,
    });
  }

  /**
   * Sends an edit of a working copy as one made from the revision, and answers with the revision it
   * led to. The backend refuses it when the catalog has changed since that revision. An edit that
   * has gone unanswered for {@link SAVE_PATIENCE} is given up and fails.
   */
  private async edit(
    method: 'POST' | 'PUT' | 'DELETE',
    address: string,
    revision: number,
    body?: unknown,
  ): Promise<number> {
    const saved = await firstValueFrom(
      this.http
        .request<{ revision: number }>(method, address, {
          body,
          headers: { 'If-Match': `"${revision}"` },
        })
        .pipe(timeout(SAVE_PATIENCE)),
    );

    return saved.revision;
  }
}
