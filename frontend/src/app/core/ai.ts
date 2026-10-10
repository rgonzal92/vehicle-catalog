import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { IN_THE_BACKGROUND } from './api-error-interceptor';

/** Whether the language model can be asked now, and why not when it cannot. */
export interface AiAvailability {
  available: boolean;
  reason: string | null;
  /** When a spent allowance is whole again, or null when that is not why it cannot be asked. */
  renewsAt?: string | null;
}

/** What the app knows of the language model it asks for suggestions. */
@Injectable({ providedIn: 'root' })
export class Ai {
  private readonly http = inject(HttpClient);

  /**
   * Whether the model can be asked. When that cannot be found out, it counts as one that cannot:
   * what asks the model is then shown as unavailable, and nothing else is held up.
   */
  async availability(): Promise<AiAvailability> {
    try {
      return await firstValueFrom(
        this.http.get<AiAvailability>('/api/ai', {
          context: new HttpContext().set(IN_THE_BACKGROUND, true),
        }),
      );
    } catch {
      return { available: false, reason: 'It could not be found out whether the model answers.' };
    }
  }
}
