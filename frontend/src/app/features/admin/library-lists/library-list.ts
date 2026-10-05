/** An entry of one of the library's ordered lists. A trim has an id and a region has a code. */
export interface LibraryEntry {
  id?: number;
  code?: string;
  name: string;
  sortOrder: number;
  active: boolean;
}

/** One of the library's ordered lists: where the backend serves it and how its screen names it. */
export interface LibraryList {
  title: string;
  singular: string;
  path: string;
  /** Whether an entry is identified by a code the admin enters once, when adding it. */
  hasCode: boolean;
}

export const TRIMS: LibraryList = {
  title: 'Trims',
  singular: 'trim',
  path: '/api/trims',
  hasCode: false,
};

export const REGIONS: LibraryList = {
  title: 'Regions',
  singular: 'region',
  path: '/api/regions',
  hasCode: true,
};
