import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { NAVIGATE, Session } from './session';
import { STEPS } from './tour-steps';

/** The login the tour signs in with, as the landing page publishes it; some have no password. */
export interface Login {
  username: string;
  password: string | null;
}

/** The class that rings the element a step is about. */
const RING = 'tour-target';

/** How often a step looks for its element again, and how long it waits for it. */
const LOOK_EVERY_MS = 250;
const WAIT_MS = 10_000;

/** Where the tab keeps the step a visitor is on, and the browser that the tour was declined. */
const KEPT = 'tour';
const DECLINED = 'tour';

/** The screens wide enough for the tour: its steps point at a page that needs a laptop's width. */
export const WIDE = '(min-width: 1024px)';

/** Where in the steps signing in comes; every step after it needs a session. */
const SIGN_IN = STEPS.findIndex((step) => step.id === 'sign-in');

/**
 * The tour: which step a visitor is on, the page it shows on, and the element it is about. The step
 * is kept in the tab, so that it opens again after a reload or the return from signing in.
 */
@Injectable({ providedIn: 'root' })
export class Tour {
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  private readonly navigate = inject(NAVIGATE);

  private readonly at = signal<number | null>(null);
  private looking?: ReturnType<typeof setInterval>;
  private focusedBefore: Element | null = null;

  /** The step open, or null while there is no tour. */
  readonly step = computed(() => {
    const at = this.at();
    return at === null ? null : STEPS[at];
  });

  /** The open step's place among the numbered ones, and how many there are: the card has none. */
  readonly number = computed(() => (this.step()?.id === 'card' ? null : (this.at() ?? 0) + 1));
  readonly total = STEPS.length - 1;

  /** The element the open step is about, once it is on the page. */
  readonly target = signal<Element | null>(null);

  /** Whether the open step's element has not appeared in the time a step waits for it. */
  readonly missing = signal(false);

  /** The demo manager's login, once the sign-in step has read it. */
  readonly login = signal<Login | null>(null);

  /** Whether the landing page offers the tour. */
  readonly offering = signal(false);

  /** Whether the tour has anything to show: its offer, or a step. */
  readonly shown = computed(() => this.offering() || this.at() !== null);

  /** Offers the tour, once per browser and on a wide screen, unless one is open. */
  offer(): void {
    const wide = window.matchMedia?.(WIDE).matches ?? false;
    const open = this.at() !== null || this.kept() !== undefined;
    this.offering.set(wide && !open && localStorage.getItem(DECLINED) === null);
  }

  /** Withdraws the offer, and remembers in the browser not to make it again. */
  decline(): void {
    localStorage.setItem(DECLINED, 'declined');
    this.offering.set(false);
  }

  /** Opens the tour on its first step. */
  async start(): Promise<void> {
    this.offering.set(false);
    this.focusedBefore = document.activeElement;
    await this.open(0);
  }

  /** Ends the tour, which counts as declining it, and gives focus back to where it was. */
  end(): void {
    this.look(undefined);
    this.at.set(null);
    sessionStorage.removeItem(KEPT);
    this.decline();
    if (this.focusedBefore instanceof HTMLElement && this.focusedBefore.isConnected) {
      this.focusedBefore.focus();
    }
    this.focusedBefore = null;
  }

  /** Opens the step the tab kept, after a reload or the return from signing in. */
  async resume(): Promise<void> {
    const kept = this.kept();
    const index = STEPS.findIndex((step) => step.id === kept);
    if (index >= 0) {
      await this.open(index, 1);
    }
  }

  /** Goes on: to sign in, from the sign-in step, and otherwise to the step after. */
  async next(): Promise<void> {
    const at = this.at();
    if (at === null) {
      return;
    }
    if (at === SIGN_IN) {
      const login = this.login();
      const hint = login === null ? '' : `?login_hint=${encodeURIComponent(login.username)}`;
      this.navigate(`/api/oauth2/authorization/cognito${hint}`);
      return;
    }
    await this.open(at + 1, 1);
  }

  /** Goes to the step before. */
  async back(): Promise<void> {
    const at = this.at();
    if (at !== null && at > 0) {
      await this.open(at - 1, -1);
    }
  }

  /**
   * Opens a step, or the one past it in the direction of travel when it is signing in and someone is
   * signed in. A step that needs a session sends someone who is not signed in to sign in.
   */
  private async open(wanted: number, direction: 1 | -1 = 1): Promise<void> {
    const signedIn = (await this.session.load()) !== null;
    let index = wanted;
    if (signedIn && index === SIGN_IN) {
      index += direction;
    } else if (!signedIn && index > SIGN_IN) {
      index = SIGN_IN;
    }
    const step = STEPS[index];
    this.at.set(index);
    sessionStorage.setItem(KEPT, JSON.stringify({ step: step.id }));
    if (step.id === 'sign-in') {
      this.login.set(await this.manager());
    }
    if (step.path !== undefined && this.router.url !== step.path) {
      await this.router.navigateByUrl(step.path);
    }
    this.look(step.target);
  }

  /**
   * Takes the ring off the element of the step before, and looks for this step's, again and again
   * until it appears or the step gives up on it.
   */
  private look(selector: string | undefined): void {
    clearInterval(this.looking);
    this.target()?.classList.remove(RING);
    this.target.set(null);
    this.missing.set(false);
    if (selector === undefined) {
      return;
    }

    let waited = -LOOK_EVERY_MS;
    const find = (): boolean => {
      waited += LOOK_EVERY_MS;
      const found = document.querySelector(selector);
      if (found !== null) {
        found.classList.add(RING);
        found.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
        this.target.set(found);
      } else if (waited >= WAIT_MS) {
        this.missing.set(true);
      } else {
        return false;
      }
      clearInterval(this.looking);
      return true;
    };
    if (!find()) {
      this.looking = setInterval(find, LOOK_EVERY_MS);
    }
  }

  /** The step the tab kept, if any. */
  private kept(): string | undefined {
    try {
      return JSON.parse(sessionStorage.getItem(KEPT) ?? '{}').step;
    } catch {
      return undefined;
    }
  }

  /** The demo manager's login, or null when the backend does not publish one. */
  private async manager(): Promise<Login | null> {
    const accounts = await firstValueFrom(
      this.http.get<(Login & { role: string })[]>('/api/demo-accounts'),
    ).catch(() => []);
    const manager = accounts.find((account) => account.role === 'manager');
    return manager === undefined
      ? null
      : { username: manager.username, password: manager.password };
  }
}
