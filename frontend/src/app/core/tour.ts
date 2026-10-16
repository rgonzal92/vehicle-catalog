import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { filter, firstValueFrom } from 'rxjs';
import { reasonOf } from '../shared/reason-of';
import { Catalogs } from './catalogs';
import { NAVIGATE, Session } from './session';
import { SEDAN, StepId, STEPS } from './tour-steps';

/** The login the tour signs in with, as the landing page publishes it; some have no password. */
interface Login {
  username: string;
  password: string | null;
}

/** What the tab keeps of a tour: its step, the working copy it made, and what its cell held. */
interface Kept {
  step?: StepId;
  copy?: number;
  held?: string;
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

/** The keys that set a cell of the matrix to each availability, by the symbol the cell shows. */
const KEY_OF: Record<string, string> = { S: 's', A: 'a', '-': '-' };

/**
 * The tour: which step a visitor is on, the page it shows on, and the element it is about. The step
 * is kept in the tab, so that it opens again after a reload or the return from signing in, and so
 * is the working copy the tour made, which it deletes when it ends.
 */
@Injectable({ providedIn: 'root' })
export class Tour {
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  private readonly catalogs = inject(Catalogs);
  private readonly messages = inject(MessageService);
  private readonly navigate = inject(NAVIGATE);

  private readonly at = signal<number | null>(null);
  private looking?: ReturnType<typeof setInterval>;
  private focusedBefore: Element | null = null;
  private copy: number | null = null;
  private held: string | null = null;

  /** The step open, or null while there is no tour. */
  readonly step = computed(() => {
    const at = this.at();
    return at === null ? null : STEPS[at];
  });

  /** The open step's place among the numbered ones, and how many there are: the card has none. */
  readonly number = computed(() => (this.step()?.id === 'card' ? null : (this.at() ?? 0) + 1));
  readonly total = STEPS.length - 1;

  /** The cell of the matrix the open step is about, for the catalog's editor to bring into view. */
  readonly cell = computed(() => this.step()?.cell ?? null);

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

  constructor() {
    // A working copy the visitor makes on the step that asks for one is the copy the tour made.
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe((event) => {
        const opened = /^\/catalogs\/(\d+)$/.exec(event.urlAfterRedirects);
        const at = this.at();
        if (opened !== null && at !== null && STEPS[at].id === 'copy') {
          this.copy = Number(opened[1]);
          void this.open(at + 1);
        }
      });
  }

  /** Offers the tour, once per browser and on a wide screen, unless one is open. */
  offer(): void {
    const wide = window.matchMedia?.(WIDE).matches ?? false;
    const open = this.at() !== null || this.kept().step !== undefined;
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

  /**
   * Ends the tour, which counts as declining it, deletes the working copy it made, and gives focus
   * back to where it was. When that is gone, as the offer is and as the page the tour started from
   * may be, the page's heading has it.
   */
  end(): void {
    this.look(undefined);
    this.at.set(null);
    sessionStorage.removeItem(KEPT);
    this.decline();
    if (this.copy !== null) {
      void this.deleteCopy(this.copy);
    }
    this.copy = null;
    this.held = null;
    const before = this.focusedBefore;
    this.focusedBefore = null;
    if (before instanceof HTMLElement && before.isConnected) {
      before.focus();
    } else {
      document.querySelector<HTMLElement>('h1[tabindex="-1"]')?.focus();
    }
  }

  /** Opens the step the tab kept, after a reload or the return from signing in. */
  async resume(): Promise<void> {
    const kept = this.kept();
    const index = STEPS.findIndex((step) => step.id === kept.step);
    if (index >= 0) {
      this.copy = kept.copy ?? null;
      this.held = kept.held ?? null;
      await this.open(index, 1);
    }
  }

  /**
   * Goes on, doing what the step asks for a visitor who would rather watch: signs in, makes the
   * working copy, or sets the cell. Every other step goes to the step after.
   */
  async next(): Promise<void> {
    const at = this.at();
    if (at === null) {
      return;
    }
    switch (STEPS[at].id) {
      case 'sign-in': {
        const login = this.login();
        const hint = login === null ? '' : `?login_hint=${encodeURIComponent(login.username)}`;
        this.navigate(`/api/oauth2/authorization/cognito${hint}`);
        return;
      }
      case 'copy':
        this.copy = await this.makeCopy();
        if (this.copy === null) {
          return;
        }
        break;
      case 'break':
        this.press('-');
        break;
      case 'fix':
        this.press(this.held ?? 's');
        break;
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
    this.keep();
    if (index === SIGN_IN) {
      this.login.set(await this.manager());
    }
    const path = step.path === undefined ? null : await this.pathOf(step.path);
    if (path !== null && this.router.url !== path) {
      await this.router.navigateByUrl(path);
    }
    this.look(step.target);
  }

  /** The address of a step's page, or null when what it names is not known. */
  private async pathOf(path: string): Promise<string | null> {
    if (path.includes('{copy}')) {
      return this.copy === null ? null : path.replace('{copy}', String(this.copy));
    }
    if (path.includes('{sedan}')) {
      const sedan = await this.sedan();
      return sedan === null ? null : path.replace('{sedan}', String(sedan.id));
    }
    return path;
  }

  /** The lineage of Sedan 2027, found by its line and year since its id changes at every reset. */
  private async sedan() {
    const lineages = await this.catalogs.lineages().catch(() => []);
    return (
      lineages.find(
        (lineage) =>
          lineage.vehicleLine === SEDAN.vehicleLine && lineage.modelYear === SEDAN.modelYear,
      ) ?? null
    );
  }

  /**
   * Makes a working copy of the current Approved Sedan 2027, named for the time to the second: an
   * owner's copies need names of their own, and everyone on the tour is the same owner, so that two
   * visitors at once each get one. A refusal is told as the app tells it, and leaves none.
   */
  private async makeCopy(): Promise<number | null> {
    try {
      const sedan = await this.sedan();
      if (sedan === null) {
        return null;
      }
      const approved = await this.catalogs.find(sedan.catalogId);
      const time = new Date().toTimeString().slice(0, 8);
      const made = await this.catalogs.create({
        name: `Tour copy ${time}`,
        vehicleLineId: approved.vehicleLineId,
        modelYear: approved.modelYear,
      });
      return made.id;
    } catch (error) {
      this.messages.add({ severity: 'error', summary: 'Not created', detail: reasonOf(error) });
      return null;
    }
  }

  /** Sets the cell the step is about as a visitor does, with the key for an availability. */
  private press(key: string): void {
    const cell = this.target();
    if (cell instanceof HTMLElement) {
      cell.focus();
      cell.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    }
  }

  /**
   * Deletes the working copy the tour made, with the revision it is at, and leaves its page. A
   * delete that fails is not tried again: the copy stays until the demo's reset.
   */
  private async deleteCopy(copy: number): Promise<void> {
    try {
      const catalog = await this.catalogs.find(copy);
      await this.catalogs.delete(copy, catalog.snapshot.revision);
      if (this.router.url === `/catalogs/${copy}`) {
        await this.router.navigateByUrl('/dashboard');
      }
    } catch {
      // The copy stays until the reset.
    }
  }

  /**
   * Takes the ring off the element of the step before, and looks for this step's, again and again
   * until it appears or the step gives up on it. The first time the cell the tour breaks is found,
   * what it holds is kept, to be set back.
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
        if (this.step()?.cell !== undefined && this.held === null) {
          this.held = KEY_OF[found.textContent?.trim().charAt(0) ?? ''] ?? null;
          this.keep();
        }
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

  /** Keeps the step, the copy, and what its cell held in the tab. */
  private keep(): void {
    const kept: Kept = {
      step: this.step()?.id,
      copy: this.copy ?? undefined,
      held: this.held ?? undefined,
    };
    sessionStorage.setItem(KEPT, JSON.stringify(kept));
  }

  /** What the tab kept of a tour, if anything. */
  private kept(): Kept {
    try {
      return JSON.parse(sessionStorage.getItem(KEPT) ?? '{}') as Kept;
    } catch {
      return {};
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
