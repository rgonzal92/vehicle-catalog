import { DatePipe } from '@angular/common';
import { Component, computed, DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { Bell } from '@primeicons/angular/bell';
import { ButtonDirective, ButtonIcon } from 'primeng/button';
import { Popover } from 'primeng/popover';
import { filter } from 'rxjs';
import { notificationInWords, Notifications } from './notifications';

/** How often the app looks for new notifications while its page is visible. */
const EVERY = 60_000;

/**
 * The button in the top strip that says how many notifications the person has not read, and opens
 * the list of them. Opening the list marks what it shows as read. The app looks for new ones on
 * every navigation, and every minute while its page is visible.
 */
@Component({
  imports: [DatePipe, RouterLink, Bell, ButtonDirective, ButtonIcon, Popover],
  selector: 'app-notifications-button',
  template: `
    <button
      pButton
      type="button"
      class="relative"
      severity="secondary"
      [text]="true"
      [iconOnly]="true"
      [attr.aria-label]="name()"
      (click)="toggle(list, $event)"
    >
      <svg data-p-icon="bell" pButtonIcon />
      @if (notifications.unread() > 0) {
        <span
          class="absolute -top-1 -right-1 min-w-5 rounded-full bg-primary px-1 text-center text-xs leading-5 font-semibold text-primary-contrast"
          aria-hidden="true"
          data-unread
          >{{ notifications.unread() }}</span
        >
      }
    </button>
    <p-popover #list ariaLabel="Notifications">
      @if (notifications.items().length === 0) {
        <p class="text-muted-color" data-no-notifications>You have no notifications.</p>
      } @else {
        <ul class="grid max-w-sm gap-3">
          @for (item of notifications.items(); track item.id) {
            <li [class.font-semibold]="!item.read">
              @if (!item.read) {
                <span class="sr-only">Unread: </span>
              }
              @if (item.payload.lineageId; as lineage) {
                <a
                  class="text-primary underline"
                  [routerLink]="['/approved', lineage]"
                  (click)="list.hide()"
                  >{{ inWords(item) }}</a
                >
              } @else {
                <span>{{ inWords(item) }}</span>
              }
              <p class="text-sm font-normal text-muted-color">
                {{ item.createdAt | date: 'medium' }}
              </p>
            </li>
          }
        </ul>
      }
    </p-popover>
  `,
})
export class NotificationsButton {
  protected readonly notifications = inject(Notifications);
  protected readonly inWords = notificationInWords;

  /** What the button is called, which says how many are unread in words. */
  protected readonly name = computed(() => {
    const unread = this.notifications.unread();
    return unread === 0 ? 'Notifications' : `Notifications, ${unread} unread`;
  });

  /** Opens the list, or closes it. What it shows when it opens is marked as read. */
  protected toggle(list: Popover, click: Event): void {
    list.toggle(click);
    if (list.overlayVisible()) {
      void this.notifications.markShownAsRead();
    }
  }

  constructor() {
    void this.notifications.refresh();
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => void this.notifications.refresh());

    const looking = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.notifications.refresh();
      }
    }, EVERY);
    inject(DestroyRef).onDestroy(() => clearInterval(looking));
  }
}
