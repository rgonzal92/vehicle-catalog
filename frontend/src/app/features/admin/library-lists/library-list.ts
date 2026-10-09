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
  /** What the list is for, in a sentence its screen shows. */
  purpose: string;
  path: string;
  /** Whether an entry is identified by a code the admin enters once, when adding it. */
  hasCode: boolean;
  /** What identifies an entry in its address. */
  keyOf: (entry: LibraryEntry) => string | number | undefined;
}

export const TRIMS: LibraryList = {
  title: 'Trims',
  singular: 'trim',
  purpose: 'Every catalog takes its trims from this list, in this order.',
  path: '/api/trims',
  hasCode: false,
  keyOf: (trim) => trim.id,
};

export const REGIONS: LibraryList = {
  title: 'Regions',
  singular: 'region',
  purpose: 'Every catalog takes its regions from this list, in this order.',
  path: '/api/regions',
  hasCode: true,
  keyOf: (region) => region.code,
};
