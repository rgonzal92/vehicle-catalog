import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { IN_THE_BACKGROUND } from './api-error-interceptor';

/** Something the person is told about what happened to their work. */
export interface Notification {
  id: number;
  /** What kind of thing happened, which decides how it is worded. */
  kind: string;
  /** What it takes to say it in words, and where it leads. */
  payload: {
    catalog?: string;
    lineageId?: number;
    vehicleLine?: string;
    modelYear?: number;
    versionNumber?: number;
    reviewer?: string;
  };
  createdAt: string;
  read: boolean;
}

/** A person's newest notifications and how many they have not read in all. */
interface Inbox {
  unread: number;
  items: Notification[];
}

/**
 * A notification in words: "Mia Manager approved Winter update as Approved v3 of Compact SUV
 * 2026". A kind this app does not know yet, which a newer backend may send, is said in general.
 */
export function notificationInWords({ kind, payload }: Notification): string {
  return kind === 'CATALOG_APPROVED'
    ? `${payload.reviewer} approved ${payload.catalog} as Approved v${payload.versionNumber} of ` +
        `${payload.vehicleLine} ${payload.modelYear}`
    : 'Something happened to your work.';
}

/** What the signed-in person has been told, as the app last read it. */
@Injectable({ providedIn: 'root' })
export class Notifications {
  private readonly http = inject(HttpClient);

  /** How many notifications the person has not read. */
  readonly unread = signal(0);

  /** Their newest notifications, newest first. */
  readonly items = signal<Notification[]>([]);

  /**
   * Reads the notifications again. Nobody asked for it, so a failure is not shown and leaves what
   * was read before.
   */
  async refresh(): Promise<void> {
    try {
      const inbox = await firstValueFrom(
        this.http.get<Inbox>('/api/notifications', {
          context: new HttpContext().set(IN_THE_BACKGROUND, true),
        }),
      );
      this.unread.set(inbox.unread);
      this.items.set(inbox.items);
    } catch {
      // The next look may succeed.
    }
  }

  /**
   * Marks the notifications that are shown as read. The list stays as it is, so the person still
   * sees which of them were new; the count is what is left unread.
   */
  async markShownAsRead(): Promise<void> {
    const newest = this.items().at(0);
    if (!newest || this.unread() === 0) {
      return;
    }
    try {
      const { unread } = await firstValueFrom(
        this.http.post<{ unread: number }>(
          '/api/notifications/read',
          { upTo: newest.id },
          { context: new HttpContext().set(IN_THE_BACKGROUND, true) },
        ),
      );
      this.unread.set(unread);
    } catch {
      // They stay unread, and are marked the next time the list is opened.
    }
  }
}
