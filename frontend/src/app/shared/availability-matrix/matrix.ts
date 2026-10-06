import { Named } from '../../core/fixed-lists';

/** What a cell states: Standard, Available, or Not offered. */
export type Availability = 'S' | 'A' | 'N';

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

/** A feature row of a catalog. */
export interface MatrixFeature {
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
  features: MatrixFeature[];
  cells: Cell[];
}

/** A region with the trims sold there, in trim order: one group of the matrix's columns. */
export interface RegionColumns {
  region: MatrixRegion;
  trims: MatrixTrim[];
}

/** A row of the matrix: a category subheader, or a feature under the subheader before it. */
export interface MatrixRow {
  category?: Named;
  feature?: MatrixFeature;
}

/**
 * The matrix's two-level header: the regions in the order given and, under each, the trims sold
 * there in trim order. A region where no trim is sold has no columns, so it is left out.
 */
export function regionColumns(
  contents: Pick<MatrixContents, 'trims' | 'regions' | 'offerings'>,
): RegionColumns[] {
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
 * The matrix's rows: each category that has features as a subheader, in the categories' display
 * order, followed by its features in code order.
 */
export function matrixRows(features: MatrixFeature[], categories: Named[]): MatrixRow[] {
  const place = new Map(categories.map((category, index) => [category.code, index]));
  const placeOf = (feature: MatrixFeature) => place.get(feature.categoryCode) ?? categories.length;
  const inOrder = [...features].sort(
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
