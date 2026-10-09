import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import {
  Availability,
  Cell,
  FeatureRow,
  Issue,
  MatrixContents,
  MatrixRegion,
  MatrixTrim,
} from '../shared/availability-matrix/matrix';
import { RuleKind } from './global-rules';

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

/** How the tag of each status is colored, the same wherever a status is shown. */
export const STATUS_SEVERITIES: Record<CatalogStatus, 'secondary' | 'info' | 'success'> = {
  DRAFT: 'secondary',
  SUBMITTED: 'info',
  APPROVED: 'success',
};

/**
 * A rule that belongs to one catalog: a source feature, its targets, and the trims and regions it
 * holds in. An exclusion holds both ways, so it is kept as two paired rules that are made, changed,
 * and deleted as one.
 */
export interface CatalogRule {
  /** What identifies the rule within its catalog. It stays with the rule through every change. */
  key: string;
  kind: RuleKind;
  sourceFeatureId: number;
  targetFeatureIds: number[];
  allTrims: boolean;
  /** The trims the rule holds on, in the library's order. They count only when not every trim. */
  trimIds: number[];
  allRegions: boolean;
  /** The regions the rule holds in, in the library's order. */
  regionCodes: string[];
  /** What a paired rule shares with its pair, or null for a rule that has none. */
  pairKey: string | null;
}

/** What an owner gives to add a catalog rule or to change one. */
export type CatalogRuleContent = Omit<CatalogRule, 'key' | 'pairKey'>;

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
  /** The name of the person who owns it. */
  owner: string;
  /** Whether its vehicle line is active. A catalog of an inactive line cannot be submitted. */
  vehicleLineActive: boolean;
  /** What its owner said when they last submitted it, if anything. */
  submitNote: string | null;
  /** When it was last submitted, or null when it never was. */
  submittedAt: string | null;
  /**
   * Whether it is a working copy whose base is no longer its lineage's current Approved. A stale
   * catalog cannot be submitted or approved until it has been updated from that version.
   */
  stale: boolean;
  /** Its lineage's current Approved, or null while the lineage has none. */
  current: { catalogId: number; versionNumber: number } | null;
  /**
   * The decision that sent it back to its owner, while it stands, which is until it is submitted
   * again: a reviewer's rejection with its reason, or a return, which is nobody's and has none.
   */
  decision: {
    decision: 'REJECTED' | 'RETURNED_STALE';
    reviewer: string | null;
    comment: string | null;
    at: string;
  } | null;
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
    /** The rules that belong to the catalog, by the code of their source. */
    rules: CatalogRule[];
  };
  /** What validation finds in it against the library as it is today, Errors before Warnings. */
  issues: Issue[];
}

/** An offering with the names of its trim and its region. */
export interface NamedOffering {
  trimId: number;
  trim: string;
  regionCode: string;
  region: string;
}

/** A cell whose availability changed, with the names of what it is a cell of. */
export interface CellChange {
  featureId: number;
  featureCode: string;
  feature: string;
  trimId: number;
  trim: string;
  regionCode: string;
  region: string;
  before: Availability;
  after: Availability;
}

/**
 * What changed from one catalog to another. What was added or changed is named by the labels of
 * the later catalog, and what was removed by those of the earlier one. An exclusion is listed once
 * for its pair.
 */
export interface CatalogChanges {
  trimsAdded: MatrixTrim[];
  trimsRemoved: MatrixTrim[];
  regionsAdded: MatrixRegion[];
  regionsRemoved: MatrixRegion[];
  offeringsAdded: NamedOffering[];
  offeringsRemoved: NamedOffering[];
  featureRowsAdded: FeatureRow[];
  featureRowsRemoved: FeatureRow[];
  /** The cells of rows and offerings that both catalogs have. */
  cellsChanged: CellChange[];
  /** Each rule by its key and in words. */
  rulesAdded: { key: string; rule: string }[];
  rulesRemoved: { key: string; rule: string }[];
  /** Each rule that says something else, with what it said before and what it says after. */
  rulesChanged: { key: string; before: string; after: string }[];
}

/** What a conflict of an update from Approved is about. */
export type ConflictKind = 'TRIM' | 'REGION' | 'OFFERING' | 'FEATURE_ROW' | 'CELL' | 'RULE';

/** Whose version of a conflict an update takes: the working copy's, or the Approved version's. */
export type ConflictSide = 'MINE' | 'THEIRS';

/** A conflict's kind as the page says it. */
export const CONFLICT_KIND_NAMES: Record<ConflictKind, string> = {
  TRIM: 'Trim',
  REGION: 'Region',
  OFFERING: 'Offering',
  FEATURE_ROW: 'Feature row',
  CELL: 'Cell',
  RULE: 'Rule',
};

/**
 * Something an update from Approved cannot settle by itself: the working copy and the Approved
 * version changed it differently, or one removed it while the other changed what is beneath it.
 */
export interface UpdateConflict {
  /** What identifies the conflict among those of one update. */
  id: string;
  kind: ConflictKind;
  /** Which one of its kind it is about, by name. */
  what: string;
  /** How it was in the catalog both come from, in words. */
  base: string;
  /** How the working copy has it. */
  mine: string;
  /** How the Approved version has it. */
  theirs: string;
}

/** What an update from its lineage's current Approved would do to a stale working copy. */
export interface UpdatePreview {
  /** The working copy's revision that the update was worked out from. */
  revision: number;
  /** The Approved version it was worked out against. */
  approved: { catalogId: number; versionNumber: number };
  /** What the update changes in the working copy without asking. */
  taken: CatalogChanges;
  conflicts: UpdateConflict[];
}

/** What a saved edit of a working copy answers with: where it led, and the issues there. */
interface Edited {
  revision: number;
  issues: Issue[];
  /** The rules that went with a feature row, a trim, or a region the edit removed, in words. */
  rulesDeleted?: string[];
  /** Whether the catalog is stale by now. */
  stale?: boolean;
}

/**
 * What removing a feature row, a trim, or a region of a working copy led to: the revision, and the
 * rules that were deleted with it, each in words and a pair once.
 */
export interface Removed {
  revision: number;
  rulesDeleted: string[];
}

const NO_ISSUES: Issue[] = [];

/** A working copy as its owner's list shows it. */
export interface WorkingCopy {
  id: number;
  name: string;
  vehicleLine: string;
  modelYear: number;
  status: CatalogStatus;
  /** What an edit made from the list, such as deleting it, names. */
  revision: number;
  updatedAt: string;
  /** How many Errors and Warnings validation finds in it against the library as it is today. */
  errors: number;
  warnings: number;
  /** Whether its base is no longer its lineage's current Approved. */
  stale: boolean;
}

/** A Submitted catalog as the review queue lists it. */
export interface SubmittedCatalog {
  id: number;
  name: string;
  vehicleLine: string;
  modelYear: number;
  /** The name of the person who owns it. */
  owner: string;
  submittedAt: string;
  /** What its owner said when they submitted it, if anything. */
  note: string | null;
  /** Whether the signed-in person owns it, who then cannot review it. */
  own: boolean;
}

/**
 * One entry of a catalog's history: a change, with who made it, when, its kind, and what it
 * touched, or a decision of a review. An entry names only what its kind is about, and the rest is
 * null.
 */
export interface Change {
  id: number;
  at: string;
  /**
   * The name of the person who made the change or the decision. Nobody returns a catalog: that
   * follows from another one's approval.
   */
  actor: string | null;
  /**
   * Such as `CELL_SET`, or a review's decision: `APPROVED`, `REJECTED`, or `RETURNED_STALE`. New
   * kinds appear without the frontend knowing them.
   */
  kind: string;
  featureCode: string | null;
  featureName: string | null;
  trim: string | null;
  region: string | null;
  /**
   * What the change replaced, and what it was replaced with: a cell's availability (S, A, or N), a
   * catalog's name, or a rule in words. A rule that was added has no old value, and one that was
   * removed no new one. A decision has its comment, or why the catalog was returned, as the new
   * value alone.
   */
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

/** The most characters a note to a reviewer, or a reviewer's comment, has. */
export const LONGEST_NOTE = 1000;

/** The most characters a catalog's name has. */
export const LONGEST_CATALOG_NAME = 80;

/** How long, in milliseconds, an edit may go unanswered before its outcome counts as unknown. */
export const SAVE_PATIENCE = 20_000;

/** A catalog's base as its pages name it. A base of an earlier model year is a carryover. */
export function baseInWords({ base, modelYear }: Pick<Catalog, 'base' | 'modelYear'>): string {
  if (!base) {
    return 'None (started empty)';
  }
  return base.modelYear === modelYear
    ? `Approved v${base.versionNumber}`
    : `${base.modelYear} Approved v${base.versionNumber} (carryover)`;
}

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

  /**
   * The issues of each catalog, and whether it is stale, as of the highest revision an answer
   * about it has carried.
   */
  private readonly issues = signal<ReadonlyMap<number, Edited>>(new Map());

  /**
   * The catalog's issues as the latest answer about it gave them, Errors before Warnings. Reading
   * the catalog and every edit of it bring them up to date.
   */
  issuesOf(catalogId: number): Issue[] {
    return this.issues().get(catalogId)?.issues ?? NO_ISSUES;
  }

  /**
   * Whether the catalog is stale, as the latest answer about it said. Another catalog of its
   * lineage can be approved while its owner is editing, and the answer to their next edit says so.
   */
  staleOf(catalogId: number): boolean {
    return this.issues().get(catalogId)?.stale ?? false;
  }

  /** Every lineage that has an Approved version, with its current one. */
  lineages(): Promise<LineageSummary[]> {
    return firstValueFrom(this.http.get<LineageSummary[]>('/api/lineages'));
  }

  /** The Approved versions of a lineage, newest first. */
  versions(lineageId: number): Promise<VersionSummary[]> {
    return firstValueFrom(this.http.get<VersionSummary[]>(`/api/lineages/${lineageId}/versions`));
  }

  async find(catalogId: number): Promise<Catalog> {
    const catalog = await firstValueFrom(this.http.get<Catalog>(`/api/catalogs/${catalogId}`));
    this.keep(catalogId, catalog.snapshot.revision, catalog.issues, catalog.stale);

    return catalog;
  }

  /** The signed-in person's working copies, the one changed last first. */
  mine(): Promise<WorkingCopy[]> {
    return firstValueFrom(
      this.http.get<WorkingCopy[]>('/api/catalogs', { params: { scope: 'mine' } }),
    );
  }

  /** The catalogs that are waiting for review, the one submitted first at the top. */
  toReview(): Promise<SubmittedCatalog[]> {
    return firstValueFrom(
      this.http.get<SubmittedCatalog[]>('/api/catalogs', { params: { scope: 'review' } }),
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

  /**
   * What changed from another catalog to this one. The other is named by its id, or as `base`: the
   * catalog this one was copied from, or nothing when it started empty.
   */
  diff(catalogId: number, against: number | 'base'): Promise<CatalogChanges> {
    return firstValueFrom(
      this.http.get<CatalogChanges>(`/api/catalogs/${catalogId}/diff`, { params: { against } }),
    );
  }

  /**
   * What an update from its lineage's current Approved would do to a stale working copy of the
   * caller's, as it is saved. It changes nothing.
   */
  previewUpdate(catalogId: number): Promise<UpdatePreview> {
    return firstValueFrom(
      this.http.post<UpdatePreview>(`/api/catalogs/${catalogId}/merge-preview`, null),
    );
  }

  /**
   * Updates a stale working copy from the Approved version the update was worked out against, with
   * the side taken of each conflict, by its id. The backend refuses when the working copy or the
   * lineage's current Approved has moved on since, and when a conflict has no side taken.
   */
  update(
    catalogId: number,
    revision: number,
    approvedCatalogId: number,
    resolutions: Record<string, ConflictSide>,
  ): Promise<number> {
    return this.edit(catalogId, 'POST', '/merge', revision, { approvedCatalogId, resolutions });
  }

  /** Renames a working copy. */
  rename(catalogId: number, revision: number, name: string): Promise<number> {
    return this.edit(catalogId, 'PATCH', '', revision, { name });
  }

  /**
   * Deletes a working copy as its owner last saw it, with its contents and its change history. The
   * backend refuses when the catalog has changed since that revision.
   */
  async delete(catalogId: number, revision: number): Promise<void> {
    await this.send('DELETE', `/api/catalogs/${catalogId}`, revision);
  }

  /**
   * Submits a working copy for review, with a note for the reviewer if there is one. The backend
   * refuses while the catalog has an Error or its vehicle line is inactive.
   */
  submit(catalogId: number, revision: number, note: string): Promise<number> {
    return this.edit(catalogId, 'POST', '/submit', revision, { note });
  }

  /**
   * Approves a Submitted catalog as the reviewer saw it, which makes it the next Approved version
   * of its lineage. The backend refuses a catalog that has an Error, is stale, or whose vehicle
   * line is inactive, and one that has changed since that revision.
   */
  approve(catalogId: number, revision: number, comment: string): Promise<number> {
    return this.edit(catalogId, 'POST', '/approve', revision, { comment });
  }

  /** Rejects a Submitted catalog as the reviewer saw it, with the reason, back to its owner. */
  reject(catalogId: number, revision: number, comment: string): Promise<number> {
    return this.edit(catalogId, 'POST', '/reject', revision, { comment });
  }

  /** Makes a Submitted catalog a Draft again. Its status guards it, so it names no revision. */
  async withdraw(catalogId: number): Promise<void> {
    await firstValueFrom(
      this.http
        .post<Edited>(`/api/catalogs/${catalogId}/withdraw`, null)
        .pipe(timeout(SAVE_PATIENCE)),
    );
  }

  /** Sets cells of a working copy. Setting a cell to Not offered removes it. */
  setCells(catalogId: number, revision: number, cells: Cell[]): Promise<number> {
    return this.edit(catalogId, 'PUT', '/cells', revision, cells);
  }

  /** Adds library trims to a working copy. A new trim is sold nowhere until its regions are set. */
  addTrims(catalogId: number, revision: number, trimIds: number[]): Promise<number> {
    return this.edit(catalogId, 'POST', '/trims', revision, { trimIds });
  }

  /**
   * Removes a trim from a working copy, and with it its offerings, their cells, and the rules that
   * covered no other trim.
   */
  removeTrim(catalogId: number, revision: number, trimId: number): Promise<Removed> {
    return this.remove(catalogId, `/trims/${trimId}`, revision);
  }

  /** Adds library regions to a working copy. */
  addRegions(catalogId: number, revision: number, regionCodes: string[]): Promise<number> {
    return this.edit(catalogId, 'POST', '/regions', revision, { regionCodes });
  }

  /**
   * Removes a region from a working copy, and with it its offerings, their cells, and the rules
   * that covered no other region.
   */
  removeRegion(catalogId: number, revision: number, regionCode: string): Promise<Removed> {
    return this.remove(catalogId, `/regions/${regionCode}`, revision);
  }

  /** Adds library features to a working copy as feature rows, each with every cell Not offered. */
  addFeatures(catalogId: number, revision: number, featureIds: number[]): Promise<number> {
    return this.edit(catalogId, 'POST', '/features', revision, { featureIds });
  }

  /**
   * Removes a feature row from a working copy, and with it its cells. The backend refuses while a
   * rule of the catalog names the feature, unless the rules that name it are to go with the row.
   */
  removeFeature(
    catalogId: number,
    revision: number,
    featureId: number,
    removeRules = false,
  ): Promise<Removed> {
    return this.remove(
      catalogId,
      `/features/${featureId}${removeRules ? '?removeRules=true' : ''}`,
      revision,
    );
  }

  /** Says in which of a working copy's regions a trim is sold: in exactly the ones given. */
  sellIn(
    catalogId: number,
    revision: number,
    trimId: number,
    regionCodes: string[],
  ): Promise<number> {
    return this.edit(catalogId, 'PUT', `/trims/${trimId}/regions`, revision, {
      regionCodes,
    });
  }

  /** Adds a rule to a working copy. An Excludes rule is added as a pair for each of its targets. */
  addRule(catalogId: number, revision: number, rule: CatalogRuleContent): Promise<number> {
    return this.edit(catalogId, 'POST', '/rules', revision, rule);
  }

  /** Changes a rule of a working copy, and its pair with it. Its kind stays what it was. */
  changeRule(
    catalogId: number,
    revision: number,
    ruleKey: string,
    rule: CatalogRuleContent,
  ): Promise<number> {
    return this.edit(catalogId, 'PUT', `/rules/${ruleKey}`, revision, rule);
  }

  /** Deletes a rule of a working copy, and its pair with it. */
  deleteRule(catalogId: number, revision: number, ruleKey: string): Promise<number> {
    return this.edit(catalogId, 'DELETE', `/rules/${ruleKey}`, revision);
  }

  /**
   * Sends an edit of a working copy as one made from the revision, answers with the revision it led
   * to, and keeps the issues the catalog then has. The backend refuses the edit when the catalog
   * has changed since that revision. An edit that has gone unanswered for {@link SAVE_PATIENCE} is
   * given up and fails.
   */
  private async edit(
    catalogId: number,
    method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    part: string,
    revision: number,
    body?: unknown,
  ): Promise<number> {
    return (await this.answerTo(catalogId, method, part, revision, body)).revision;
  }

  /** Sends the removal of a part of a working copy, as an edit, and says what went with it. */
  private async remove(catalogId: number, part: string, revision: number): Promise<Removed> {
    const saved = await this.answerTo(catalogId, 'DELETE', part, revision);

    // A backend that is one release behind answers without the rules it deleted.
    return { revision: saved.revision, rulesDeleted: saved.rulesDeleted ?? [] };
  }

  private async answerTo(
    catalogId: number,
    method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    part: string,
    revision: number,
    body?: unknown,
  ): Promise<Edited> {
    const saved = await this.send<Edited>(
      method,
      `/api/catalogs/${catalogId}${part}`,
      revision,
      body,
    );
    this.keep(catalogId, saved.revision, saved.issues, saved.stale);

    return saved;
  }

  /**
   * Takes a catalog's issues from an answer, unless a later answer has been heard already: one that
   * was slow to arrive says what the issues were, not what they are.
   */
  private keep(
    catalogId: number,
    revision: number,
    issues: Issue[] | undefined,
    stale: boolean | undefined,
  ): void {
    const known = this.issues().get(catalogId);
    if (!known || revision >= known.revision) {
      // A backend that is one release behind answers without issues, and without whether the
      // catalog is stale.
      this.issues.update((all) =>
        new Map(all).set(catalogId, { revision, issues: issues ?? NO_ISSUES, stale: !!stale }),
      );
    }
  }

  /** Sends a request about a working copy as one made from the revision, and gives up in time. */
  private send<Answer>(
    method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    address: string,
    revision: number,
    body?: unknown,
  ): Promise<Answer> {
    return firstValueFrom(
      this.http
        .request<Answer>(method, address, { body, headers: { 'If-Match': `"${revision}"` } })
        .pipe(timeout(SAVE_PATIENCE)),
    );
  }
}
