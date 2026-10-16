import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { filter, firstValueFrom } from 'rxjs';
import { reasonOf } from '../shared/reason-of';
import { Catalogs, LineageSummary } from './catalogs';
import { NAVIGATE, Session } from './session';
import { CellAt, cellOnPage, SEDAN, StepId, STEPS, TourStep } from './tour-steps';

/** The login the tour signs in with, as the landing page publishes it; some have no password. */
interface Login {
  username: string;
  password: string | null;
}

/** What a cell of the matrix shows for each availability. */
type Symbol = 'S' | 'A' | '-';

/** What the tab keeps of a tour: its step, the working copy it made, and what its cell held. */
interface Kept {
  step?: StepId;
  copy?: number;
  held?: Symbol;
}

/** The class that rings the element a step is about. */
const RING = 'tour-target';

/** How often a step looks for its element again, and how long it waits for it. */
const LOOK_EVERY_MS = 250;
const WAIT_MS = 10_000;

/** How often a step asks again for its cell to be shown while the matrix has not drawn it. */
const ASK_FOR_THE_CELL_EVERY_MS = 1_000;

/** How long the analyst's step waits for its answer, which the model can take a while over. */
const ANSWER_WAIT_MS = 60_000;

/** Where a step shows, the elements it points at, and what it says. */
interface Shown {
  path: string | null;
  target?: string;
  words: string;
}

/** Where the tab keeps the step a visitor is on, and the browser that the tour was declined. */
const KEPT = 'tour';
const DECLINED = 'tour';

/** The screens wide enough for the tour: its steps point at a page that needs a laptop's width. */
export const WIDE = '(min-width: 1024px)';

/** Where in the steps signing in comes; every step after it needs a session. */
const SIGN_IN = STEPS.findIndex((step) => step.id === 'sign-in');

/** The keys that set a cell of the matrix to each availability, by what the cell shows. */
const KEY_OF: Record<Symbol, string> = { S: 's', A: 'a', '-': '-' };

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
  private ringed: Element[] = [];
  private opening: Promise<void> = Promise.resolve();
  private lookedFor: Promise<void> = Promise.resolve();
  private copy: number | null = null;
  private held: Symbol | null = null;
  private asked = false;

  /** The step open, or null while there is no tour. */
  readonly step = computed(() => {
    const at = this.at();
    return at === null ? null : STEPS[at];
  });

  /** The open step's place among the numbered ones, and how many there are: the card has none. */
  readonly number = computed(() => (this.step()?.id === 'card' ? null : (this.at() ?? 0) + 1));
  readonly total = STEPS.length - 1;

  /**
   * The cell of the matrix the open step is about, for the catalog's editor to bring into view. A
   * matrix that has only just been drawn may not yet scroll that far, so while the cell is not on
   * the page it is asked for again, as a new value.
   */
  readonly cell = signal<CellAt | null>(null);

  /** The question the open step fills in for the analyst. */
  readonly question = computed(() => this.step()?.question ?? null);

  /** What the open step says, which for the review's step depends on what there is to review. */
  readonly words = signal('');

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
    // One that is opened and was not made on this page is somebody's own, and is left alone.
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe((event) => {
        const opened = /^\/catalogs\/(\d+)$/.exec(event.urlAfterRedirects);
        const at = this.at();
        const made = opened !== null && Number(opened[1]) === this.catalogs.lastMade();
        if (made && at !== null && STEPS[at].id === 'copy' && this.copy === null) {
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
    this.cell.set(null);
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
   * working copy unless it is made, or sets the cell. A step that cannot be shown goes on without
   * doing it, and every other step goes to the step after.
   */
  async next(): Promise<void> {
    await this.opening;
    const at = this.at();
    if (at === null) {
      return;
    }
    const step = STEPS[at];
    // A step that does something to its element waits until the element is there or given up on.
    if (step.id === 'break' || step.id === 'fix' || (step.id === 'analyst' && !this.asked)) {
      await this.lookedFor;
    }
    if (step.id === 'sign-in') {
      const login = this.login();
      const hint = login === null ? '' : `?login_hint=${encodeURIComponent(login.username)}`;
      this.navigate(`/api/oauth2/authorization/cognito${hint}`);
      return;
    }
    if (!this.missing()) {
      if (step.id === 'copy' && this.copy === null) {
        this.copy = await this.makeCopy();
        if (this.copy === null) {
          this.missing.set(true);
          return;
        }
      } else if (step.id === 'break') {
        this.press('-');
      } else if (step.id === 'fix') {
        this.press(this.held ?? 'S');
      } else if (step.id === 'analyst' && !this.asked && this.target() instanceof HTMLFormElement) {
        // The step stays open, now pointing at the answer once it arrives.
        this.asked = true;
        (this.target() as HTMLFormElement).requestSubmit();
        this.look('[data-by="ANALYST"]', ANSWER_WAIT_MS);
        return;
      }
    }
    await this.open(at + 1, 1);
  }

  /** Goes to the step before. */
  async back(): Promise<void> {
    await this.opening;
    const at = this.at();
    if (at !== null && at > 0) {
      await this.open(at - 1, -1);
    }
  }

  /**
   * Opens a step, or the one past it in the direction of travel when it is signing in and someone is
   * signed in. A step that needs a session sends someone who is not signed in to sign in. Going on
   * or back waits for a step still opening, which may be finding its page.
   */
  private open(wanted: number, direction: 1 | -1 = 1): Promise<void> {
    this.opening = this.opened(wanted, direction);
    return this.opening;
  }

  private async opened(wanted: number, direction: 1 | -1): Promise<void> {
    const signedIn = (await this.session.load()) !== null;
    let index = wanted;
    if (signedIn && index === SIGN_IN) {
      index += direction;
    } else if (!signedIn && index > SIGN_IN) {
      index = SIGN_IN;
    }
    const step = STEPS[index];
    this.at.set(index);
    // A step about the cell the step before was about leaves the editor's view of it as it is.
    if (JSON.stringify(step.cell ?? null) !== JSON.stringify(this.cell())) {
      this.cell.set(step.cell ?? null);
    }
    this.keep();
    if (index === SIGN_IN) {
      this.login.set(await this.manager());
    }
    const shown = await this.shownAs(step);
    this.words.set(shown.words);
    this.asked = false;
    if (shown.path !== null && this.router.url !== shown.path) {
      await this.router.navigateByUrl(shown.path);
    }
    this.look(shown.target);
  }

  /**
   * Where a step shows, what it points at, and what it says. The review's step shows the first
   * catalog waiting for a review that is not the manager's own; with none, the empty queue on the
   * dashboard; and to an author, who reviews nothing, only its words.
   */
  private async shownAs(step: TourStep): Promise<Shown> {
    if (step.id !== 'review') {
      const path = step.path === undefined ? null : await this.pathOf(step.path);
      return { path, target: step.target, words: step.words };
    }
    if (!this.session.holds('manager')) {
      return { path: null, words: step.forAuthors ?? step.words };
    }
    const queue = await this.catalogs.toReview().catch(() => []);
    const waiting = queue.find((catalog) => !catalog.own);
    if (waiting === undefined) {
      return {
        path: '/dashboard',
        target: '[data-tour="review-queue"]',
        words: step.noneWaiting ?? step.words,
      };
    }
    return {
      path: step.path?.replace('{review}', String(waiting.id)) ?? null,
      target: step.target,
      words: step.words,
    };
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
  private async sedan(): Promise<LineageSummary | null> {
    const lineages = await this.catalogs.lineages().catch(() => []);
    return (
      lineages.find(
        (lineage) =>
          lineage.vehicleLine === SEDAN.vehicleLine && lineage.modelYear === SEDAN.modelYear,
      ) ?? null
    );
  }

  /**
   * Makes a working copy of the current Approved Sedan 2027, named for the time to the second. An
   * owner's copies need names of their own and everyone on the tour is the same owner, so a name
   * another tour took in the same second gets a number after it. A refusal is told as the app
   * tells it, and leaves none.
   */
  private async makeCopy(): Promise<number | null> {
    try {
      const sedan = await this.sedan();
      if (sedan === null) {
        return null;
      }
      const approved = await this.catalogs.find(sedan.catalogId);
      const time = new Date().toTimeString().slice(0, 8);
      for (let tried = 1; ; tried++) {
        try {
          const made = await this.catalogs.create({
            name: tried === 1 ? `Tour copy ${time}` : `Tour copy ${time} (${tried})`,
            vehicleLineId: approved.vehicleLineId,
            modelYear: approved.modelYear,
          });
          return made.id;
        } catch (error) {
          const taken = error instanceof HttpErrorResponse && error.error?.code === 'NAME_TAKEN';
          if (!taken || tried === 3) {
            throw error;
          }
        }
      }
    } catch (error) {
      this.messages.add({ severity: 'error', summary: 'Not created', detail: reasonOf(error) });
      return null;
    }
  }

  /** Sets the cell the step is about as a visitor does, with the key for what it is to show. */
  private press(shown: Symbol): void {
    // The key goes to the cell without the focus, which stays on the tour.
    this.cellOnPage()?.dispatchEvent(
      new KeyboardEvent('keydown', { key: KEY_OF[shown], bubbles: true }),
    );
  }

  /** The cell of the matrix the open step is about, if it is drawn. */
  private cellOnPage(): HTMLElement | null {
    const cell = this.step()?.cell;
    return cell === undefined ? null : document.querySelector<HTMLElement>(cellOnPage(cell));
  }

  /**
   * Deletes the working copy the tour made, with the revision it is at, and leaves its page. An
   * edit still being saved can leave that revision behind by the time the delete arrives, so a
   * delete that is refused is asked once more, from the copy as it is read then. One refused again
   * is not: the copy stays until the demo's reset.
   */
  private async deleteCopy(copy: number): Promise<void> {
    const remove = async () =>
      this.catalogs.delete(copy, (await this.catalogs.find(copy)).snapshot.revision);
    try {
      await remove().catch(remove);
      if (this.router.url === `/catalogs/${copy}`) {
        await this.router.navigateByUrl('/dashboard');
      }
    } catch {
      // The copy stays until the reset.
    }
  }

  /**
   * Takes the rings off the elements of the step before, and looks for this step's, again and
   * again until they appear or the step gives up on them. The first time the cell the tour breaks
   * is found, what it shows is kept, to be set back.
   */
  private look(selector: string | undefined, waitMs = WAIT_MS): void {
    clearInterval(this.looking);
    this.ringed.forEach((element) => element.classList.remove(RING));
    this.ringed = [];
    this.target.set(null);
    this.missing.set(false);
    if (selector === undefined) {
      this.lookedFor = Promise.resolve();
      return;
    }

    let looked = () => {};
    this.lookedFor = new Promise((resolve) => (looked = resolve));
    let waited = -LOOK_EVERY_MS;
    const find = (): boolean => {
      waited += LOOK_EVERY_MS;
      const found = Array.from(document.querySelectorAll(selector));
      if (found.length > 0) {
        found.forEach((element) => element.classList.add(RING));
        // A cell is brought into view by the editor, which scrolls the matrix to it.
        if (this.step()?.cell === undefined) {
          found[0].scrollIntoView?.({ block: 'center', behavior: 'smooth' });
        }
        this.ringed = found;
        this.target.set(found[0]);
        const shown = this.cellOnPage()?.textContent?.trim().charAt(0);
        if (this.held === null && (shown === 'S' || shown === 'A' || shown === '-')) {
          this.held = shown;
          this.keep();
        }
      } else if (waited >= waitMs) {
        this.missing.set(true);
      } else {
        const cell = this.cell();
        if (cell !== null && waited > 0 && waited % ASK_FOR_THE_CELL_EVERY_MS === 0) {
          this.cell.set({ ...cell });
        }
        return false;
      }
      clearInterval(this.looking);
      looked();
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
