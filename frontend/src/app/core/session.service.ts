import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The signed-in person, as the backend describes them. */
export interface Person {
  id: number;
  name: string;
  email: string | null;
  roles: string[];
}

/** Sends the browser to another address. Tests replace it, since a test page cannot navigate. */
export const NAVIGATE = new InjectionToken<(url: string) => void>('navigate', {
  factory: () => (url) => window.location.assign(url),
});

/** Knows who is signed in, and signs them out. */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly navigate = inject(NAVIGATE);

  /** Undefined until the backend has been asked. */
  private readonly asked = signal<Person | null | undefined>(undefined);

  /** The signed-in person, or null without a session. */
  readonly person = computed(() => this.asked() ?? null);

  /** Asks the backend who is signed in. It is asked once; later calls answer from memory. */
  async load(): Promise<Person | null> {
    const known = this.asked();
    if (known !== undefined) {
      return known;
    }

    try {
      this.asked.set(await firstValueFrom(this.http.get<Person>('/api/me')));
    } catch (error) {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        throw error;
      }
      this.asked.set(null);
    }

    return this.person();
  }

  /**
   * Ends the session, then leaves for the address the backend names: the login provider's logout,
   * which returns to the landing page.
   */
  async signOut(): Promise<void> {
    const { logoutUrl } = await firstValueFrom(
      this.http.post<{ logoutUrl: string }>('/api/logout', null),
    );

    this.navigate(logoutUrl);
  }
}
