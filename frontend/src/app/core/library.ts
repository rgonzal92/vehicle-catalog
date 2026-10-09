import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A trim as the library defines it. */
export interface LibraryTrim {
  id: number;
  name: string;
  sortOrder: number;
  active: boolean;
}

/** A region as the library defines it. Its code is its identity. */
export interface LibraryRegion {
  code: string;
  name: string;
  sortOrder: number;
  active: boolean;
}

/** Whether a feature stands alone or is a package, which brings other features with it. */
export type FeatureKind = 'FEATURE' | 'PACKAGE';

/** The name shown for each kind of feature. */
export const KIND_NAMES: Record<FeatureKind, string> = { FEATURE: 'Feature', PACKAGE: 'Package' };

/** What a filter on kind offers: every kind, or one of them. */
export const KIND_FILTERS: { code: FeatureKind | ''; name: string }[] = [
  { code: '', name: 'Every kind' },
  { code: 'FEATURE', name: KIND_NAMES.FEATURE },
  { code: 'PACKAGE', name: KIND_NAMES.PACKAGE },
];

/** A feature as the library defines it, for a catalog to add as a feature row. */
export interface LibraryFeature {
  id: number;
  code: string;
  name: string;
  categoryCode: string;
  kind: FeatureKind;
}

/** One page of the features a search found, and how many it found in all. */
export interface FeaturePage {
  items: LibraryFeature[];
  total: number;
}

/**
 * A search of the library's active features, for a catalog to add from. An empty filter lets every
 * feature through.
 */
export interface ActiveFeatureSearch {
  /** Part of a code or a name. */
  query: string;
  category: string;
  kind: FeatureKind | '';
  /** Pages are numbered from 0. */
  page: number;
  size: number;
}

/** Reads the library's trims, regions, and features, which every catalog adds from. */
@Injectable({ providedIn: 'root' })
export class Library {
  private readonly http = inject(HttpClient);

  /** Every trim, in the library's order. */
  trims(): Promise<LibraryTrim[]> {
    return firstValueFrom(this.http.get<LibraryTrim[]>('/api/trims'));
  }

  /** Every region, in the library's order. */
  regions(): Promise<LibraryRegion[]> {
    return firstValueFrom(this.http.get<LibraryRegion[]>('/api/regions'));
  }

  /**
   * One page of the active features a search finds, in code order, and how many it finds in all. A
   * retired feature can no longer be added to a catalog, so it is never among them.
   */
  activeFeatures(search: ActiveFeatureSearch): Promise<FeaturePage> {
    const params = Object.fromEntries(
      Object.entries({ ...search, status: 'ACTIVE' }).filter(([, value]) => value !== ''),
    );
    return firstValueFrom(this.http.get<FeaturePage>('/api/features', { params }));
  }

  /**
   * Every active feature, in code order, for a rule to name. The backend gives them a page at a
   * time, so this asks for page after page until it has them all.
   */
  async everyActiveFeature(): Promise<LibraryFeature[]> {
    const all: LibraryFeature[] = [];
    for (let page = 0; ; page++) {
      const found = await this.activeFeatures({
        query: '',
        category: '',
        kind: '',
        page,
        size: LARGEST_PAGE,
      });
      all.push(...found.items);
      if (found.items.length === 0 || all.length >= found.total) {
        return all;
      }
    }
  }
}

/** The most features the backend gives in one page. */
const LARGEST_PAGE = 100;
