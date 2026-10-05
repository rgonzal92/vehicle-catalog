import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** An entry of a fixed list: its code and the name shown for it. */
export interface Named {
  code: string;
  name: string;
}

interface ReferenceLists {
  vehicleTypes: Named[];
  categories: Named[];
  modelYears: number[];
}

/** The fixed lists the app is built on. They never change, so the backend is asked once. */
@Injectable({ providedIn: 'root' })
export class Reference {
  private readonly http = inject(HttpClient);
  private readonly lists = signal<ReferenceLists | null>(null);

  readonly vehicleTypes = () => this.lists()?.vehicleTypes ?? [];

  async load(): Promise<void> {
    if (!this.lists()) {
      this.lists.set(await firstValueFrom(this.http.get<ReferenceLists>('/api/reference')));
    }
  }

  /** The name shown for a vehicle type, or its code until the lists have loaded. */
  vehicleTypeName(code: string): string {
    return this.vehicleTypes().find((type) => type.code === code)?.name ?? code;
  }
}
