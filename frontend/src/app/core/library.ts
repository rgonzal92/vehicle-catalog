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

/** Reads the library's trims and regions, which every catalog adds from. */
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
}
