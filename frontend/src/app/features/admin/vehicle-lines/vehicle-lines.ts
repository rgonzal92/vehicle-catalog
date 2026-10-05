import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A product sold across model years, such as "Compact SUV". */
export interface VehicleLine {
  id: number;
  code: string;
  name: string;
  vehicleTypeCode: string;
  active: boolean;
}

/** What an admin gives to add a vehicle line. Its code cannot change afterwards. */
export type NewVehicleLine = Pick<VehicleLine, 'code' | 'name' | 'vehicleTypeCode'>;

/** What an admin gives to rename, retype, activate, or deactivate a vehicle line. */
export type VehicleLineChange = Pick<VehicleLine, 'name' | 'vehicleTypeCode' | 'active'>;

/**
 * The library's vehicle lines, in the backend's order. The list is read again after every change, and
 * a refused request leaves it as it was.
 */
@Injectable({ providedIn: 'root' })
export class VehicleLines {
  private readonly http = inject(HttpClient);
  private readonly loaded = signal<VehicleLine[]>([]);

  readonly lines = this.loaded.asReadonly();

  async load(): Promise<void> {
    this.loaded.set(await firstValueFrom(this.http.get<VehicleLine[]>('/api/vehicle-lines')));
  }

  async add(line: NewVehicleLine): Promise<void> {
    await firstValueFrom(this.http.post<VehicleLine>('/api/vehicle-lines', line));
    await this.load();
  }

  async change(id: number, change: VehicleLineChange): Promise<void> {
    await firstValueFrom(this.http.put<VehicleLine>(`/api/vehicle-lines/${id}`, change));
    await this.load();
  }
}
