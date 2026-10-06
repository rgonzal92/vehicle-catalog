import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';

/** Whether a feature stands alone or is a package, which brings other features with it. */
export type FeatureKind = 'FEATURE' | 'PACKAGE';

/** A retired feature can no longer be added to catalogs or rules. */
export type FeatureStatus = 'ACTIVE' | 'RETIRED';

/** Anything a vehicle can be equipped with, defined once in the library. */
export interface Feature {
  id: number;
  code: string;
  name: string;
  description: string;
  categoryCode: string;
  kind: FeatureKind;
  status: FeatureStatus;
  /** Counts the changes made to the feature; an edit names the version it was made from. */
  version: number;
}

/** What an admin gives to add a feature. Its code and kind cannot change afterwards. */
export type NewFeature = Pick<Feature, 'code' | 'name' | 'description' | 'categoryCode' | 'kind'>;

/** What an admin gives to edit a feature, with the version of the feature they saw. */
export type FeatureChange = Pick<Feature, 'name' | 'description' | 'categoryCode' | 'version'>;

/** The filters and the page of a search. An empty filter lets every feature through. */
export interface FeatureSearch {
  query: string;
  category: string;
  kind: FeatureKind | '';
  status: FeatureStatus | '';
  /** Pages are numbered from 0. */
  page: number;
  size: number;
}

interface Found {
  items: Feature[];
  total: number;
}

/**
 * One page of the feature library: the features the latest search found. The page is read again
 * after every change, accepted or refused, so an edit always starts from the feature as it now is.
 */
@Injectable({ providedIn: 'root' })
export class FeatureLibrary {
  private readonly http = inject(HttpClient);
  private readonly found = signal<Found>({ items: [], total: 0 });
  private latest: FeatureSearch = {
    query: '',
    category: '',
    kind: '',
    status: '',
    page: 0,
    size: 25,
  };

  readonly features = computed(() => this.found().items);

  /** How many features the search found across all of its pages. */
  readonly total = computed(() => this.found().total);

  async find(search: FeatureSearch): Promise<void> {
    this.latest = search;
    const params = Object.fromEntries(Object.entries(search).filter(([, value]) => value !== ''));
    const found = await firstValueFrom(this.http.get<Found>('/api/features', { params }));

    // A slow answer to an earlier search must not replace the answer to this one.
    if (search === this.latest) {
      this.found.set(found);
    }
  }

  add(feature: NewFeature): Promise<void> {
    return this.send(this.http.post('/api/features', feature));
  }

  change(id: number, change: FeatureChange): Promise<void> {
    return this.send(this.http.put(`/api/features/${id}`, change));
  }

  retire(id: number): Promise<void> {
    return this.send(this.http.post(`/api/features/${id}/retire`, null));
  }

  reactivate(id: number): Promise<void> {
    return this.send(this.http.post(`/api/features/${id}/reactivate`, null));
  }

  private async send(change: Observable<unknown>): Promise<void> {
    try {
      await firstValueFrom(change);
    } finally {
      await this.find(this.latest);
    }
  }
}
