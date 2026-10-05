import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** What a person may do. Each role includes the ones below it. */
export type Role = 'admin' | 'manager' | 'author';

/** The signed-in person, as the backend describes them. */
export interface Person {
  id: number;
  name: string;
  email: string | null;
  /** Every role the person holds, highest first; empty when no role is assigned. */
  roles: Role[];
}

/** Sends the browser to another address. Tests replace it, since a test page cannot navigate. */
export const NAVIGATE = new InjectionToken<(url: string) => void>('navigate', {
  factory: () => (url) => window.location.assign(url),
});

/** Knows who is signed in, and signs them out. */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);
  private readonly navigate = inject(NAVIGATE);

  /** Undefined until the backend has answered who is signed in. */
  private readonly loaded = signal<Person | null | undefined>(undefined);

  /** The signed-in person, or null without a session. */
  readonly person = computed(() => this.loaded() ?? null);

  /** Whether the signed-in person holds the role, directly or through a higher one. */
  holds(role: Role): boolean {
    return this.person()?.roles.includes(role) ?? false;
  }

  /**
   * Asks the backend who is signed in. Its answer is remembered; when it fails to answer, nobody
   * counts as signed in and the next call asks again.
   */
  async load(): Promise<Person | null> {
    const known = this.loaded();
    if (known !== undefined) {
      return known;
    }

    try {
      this.loaded.set(await firstValueFrom(this.http.get<Person>('/api/me')));
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this.loaded.set(null);
      }
    }

    return this.person();
  }

  /** Forgets the person once the backend says their session has ended. */
  expire(): void {
    this.loaded.set(null);
  }

  /**
   * Ends the session, then leaves for the address the backend names: the login provider's logout,
   * which returns to the landing page. A failed request changes nothing; the person stays put.
   */
  async signOut(): Promise<void> {
    try {
      const { logoutUrl } = await firstValueFrom(
        this.http.post<{ logoutUrl: string }>('/api/logout', null),
      );
      this.navigate(logoutUrl);
    } catch {
      // The failure has already been shown as a message.
    }
  }
}
