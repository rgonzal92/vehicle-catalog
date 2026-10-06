import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Role } from '../../../core/session';

/** A person's account as an admin sees it. */
export interface ListedUser {
  username: string;
  email: string | null;
  /** The highest role the account holds, or null when it holds none. */
  role: Role | null;
  /** When the person last signed in to the app, or null when they never have. */
  lastLogin: string | null;
  /** Whether the account's role can be changed. */
  changeable: boolean;
}

/** What each role is called on screen, highest first. */
export const ROLE_NAMES: { role: Role; name: string }[] = [
  { role: 'admin', name: 'Admin' },
  { role: 'manager', name: 'Manager' },
  { role: 'author', name: 'Author' },
];

/**
 * The accounts the signed-in admin may see, in the backend's order. A refused request leaves the
 * list as it was.
 */
@Injectable({ providedIn: 'root' })
export class Users {
  private readonly http = inject(HttpClient);
  private readonly loaded = signal<ListedUser[]>([]);

  readonly users = this.loaded.asReadonly();

  async load(): Promise<void> {
    this.loaded.set(await firstValueFrom(this.http.get<ListedUser[]>('/api/admin/users')));
  }

  /**
   * Gives the account the role, which also ends the sessions of its person. It is done once this
   * resolves; the list is then read again without being waited for, since a list that cannot be
   * read does not undo the change.
   */
  async setRole(username: string, role: Role): Promise<void> {
    const address = `/api/admin/users/${encodeURIComponent(username)}/role`;
    await firstValueFrom(this.http.put<ListedUser>(address, { role }));
    void this.load().catch(() => {
      // The failure has already been shown as a message.
    });
  }
}
