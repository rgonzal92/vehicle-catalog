import { Named } from '../../core/fixed-lists';

/** What a cell states: Standard, Available, or Not offered. */
export type Availability = 'S' | 'A' | 'N';

/** The name of each availability, where the matrix itself shows S, A, and a dash. */
export const AVAILABILITY_NAMES: Record<Availability, string> = {
  S: 'Standard',
  A: 'Available',
  N: 'Not offered',
};

/** A trim a catalog has added, with the name and place in the order the matrix shows. */
export interface MatrixTrim {
  id: number;
  name: string;
  sortOrder: number;
}

/** A region a catalog has added. */
export interface MatrixRegion {
  code: string;
  name: string;
}

/** One trim sold in one region: a column of the matrix. */
export interface Offering {
  trimId: number;
  regionCode: string;
}

/** A feature a catalog has added: one row of the matrix. */
export interface FeatureRow {
  id: number;
  code: string;
  name: string;
  categoryCode: string;
}

/** The availability of one feature in one offering. */
export interface Cell {
  featureId: number;
  trimId: number;
  regionCode: string;
  availability: Availability;
}

/** What the matrix shows of a catalog. Cells are sparse: a missing cell is Not offered. */
export interface MatrixContents {
  trims: MatrixTrim[];
  /** In the order the matrix shows them, left to right. */
  regions: MatrixRegion[];
  offerings: Offering[];
  featureRows: FeatureRow[];
  cells: Cell[];
}

/** A region with the trims sold there, in trim order: its offerings, side by side in the matrix. */
export interface RegionOfferings {
  region: MatrixRegion;
  trims: MatrixTrim[];
}

/** A row of the matrix: a category subheader, or a feature row under the subheader before it. */
export interface MatrixRow {
  category?: Named;
  feature?: FeatureRow;
}

/**
 * The matrix's two-level header: the regions in the order given and, under each, the trims sold
 * there in trim order. A region where no trim is sold has no offerings, so it is left out.
 */
export function offeringsByRegion(
  contents: Pick<MatrixContents, 'trims' | 'regions' | 'offerings'>,
): RegionOfferings[] {
  const inOrder = [...contents.trims].sort(
    (one, other) => one.sortOrder - other.sortOrder || one.id - other.id,
  );
  const sold = new Set(
    contents.offerings.map(({ trimId, regionCode }) => `${trimId}:${regionCode}`),
  );

  return contents.regions
    .map((region) => ({
      region,
      trims: inOrder.filter((trim) => sold.has(`${trim.id}:${region.code}`)),
    }))
    .filter((group) => group.trims.length > 0);
}

/**
 * The matrix's rows: each category that has feature rows as a subheader, in the categories' display
 * order, followed by its feature rows in code order.
 */
export function matrixRows(featureRows: FeatureRow[], categories: Named[]): MatrixRow[] {
  const place = new Map(categories.map((category, index) => [category.code, index]));
  const placeOf = (feature: FeatureRow) => place.get(feature.categoryCode) ?? categories.length;
  const inOrder = [...featureRows].sort(
    (one, other) =>
      placeOf(one) - placeOf(other) ||
      one.categoryCode.localeCompare(other.categoryCode) ||
      one.code.localeCompare(other.code),
  );

  const rows: MatrixRow[] = [];
  let current: string | undefined;
  for (const feature of inOrder) {
    if (feature.categoryCode !== current) {
      current = feature.categoryCode;
      rows.push({
        category: categories[placeOf(feature)] ?? { code: current, name: current },
      });
    }
    rows.push({ feature });
  }

  return rows;
}
