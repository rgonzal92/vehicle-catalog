import { HttpClient } from '@angular/common/http';
import { Injector } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { of, Subject } from 'rxjs';
import { Catalogs } from './catalogs';
import { NAVIGATE, Person, Session } from './session';
import { Tour, WIDE } from './tour';

describe('Tour', () => {
  /** Who the backend says is signed in: nobody, until a test signs someone in. */
  let signedIn: Person | null;

  /** The page the app shows, as the router has it. */
  let url: string;

  /** Addresses the browser was sent to, outside the app. */
  let left: string[];

  /** The router's navigations, each told as it ends. */
  let navigations: Subject<NavigationEnd>;

  /** Working copies made, and deleted with the revision named, as the backend was asked. */
  let made: { name: string; vehicleLineId: number; modelYear: number }[];
  let deleted: [number, number][];

  const manager: Person = {
    id: 2,
    name: 'Demo Manager',
    email: null,
    roles: ['manager', 'author'],
  };

  /** A tour as one load of the page has it, with fakes of the session, the router, and the browser. */
  function load(): Tour {
    return Injector.create({
      providers: [
        Tour,
        { provide: Session, useValue: { load: async () => signedIn } },
        {
          provide: Router,
          useValue: {
            get url() {
              return url;
            },
            events: navigations,
            navigateByUrl: async (to: string) => {
              url = to;
              navigations.next(new NavigationEnd(1, to, to));
              return true;
            },
          },
        },
        {
          provide: Catalogs,
          useValue: {
            lineages: async () => [
              { id: 3, vehicleLine: 'Sedan', modelYear: 2026, catalogId: 30 },
              { id: 7, vehicleLine: 'Sedan', modelYear: 2027, catalogId: 70 },
              { id: 9, vehicleLine: 'Compact SUV', modelYear: 2027, catalogId: 90 },
            ],
            find: async (catalogId: number) => ({
              vehicleLineId: 5,
              modelYear: 2027,
              snapshot: { catalogId, revision: catalogId === 41 ? 9 : 1 },
            }),
            create: async (given: { name: string; vehicleLineId: number; modelYear: number }) => {
              made.push(given);
              return { id: 41 };
            },
            delete: async (catalogId: number, revision: number) => {
              deleted.push([catalogId, revision]);
            },
          },
        },
        { provide: NAVIGATE, useValue: (to: string) => left.push(to) },
        { provide: MessageService, useValue: { add: () => undefined } },
        {
          provide: HttpClient,
          useValue: {
            get: () =>
              of([
                {
                  role: 'author',
                  username: 'author',
                  password: 'author-password',
                },
                {
                  role: 'manager',
                  username: 'manager',
                  password: 'manager-password',
                },
              ]),
          },
        },
      ],
    }).get(Tour);
  }

  /** Puts an element of the page that a step is about into the document. */
  function onThePage(about: string): HTMLElement {
    const element = document.createElement('div');
    element.dataset['tour'] = about;
    document.body.append(element);
    return element;
  }

  beforeEach(() => {
    signedIn = null;
    url = '/';
    left = [];
    navigations = new Subject();
    made = [];
    deleted = [];
  });

  afterEach(() => {
    document.body.replaceChildren();
    sessionStorage.clear();
    localStorage.clear();
    vi.useRealTimers();
  });

  it('opens on what the app is for, on the landing page, with a ring on its heading', async () => {
    const heading = onThePage('about');
    const tour = load();

    await tour.start();

    expect(tour.step()?.id).toBe('about');
    expect(url).toBe('/');
    expect(tour.target()).toBe(heading);
    expect(heading.classList).toContain('tour-target');
  });

  it("shows the demo manager's login, and signs in with its username filled in", async () => {
    const tour = load();
    await tour.start();

    await tour.next();

    expect(tour.step()?.id).toBe('sign-in');
    expect(tour.login()).toEqual({
      username: 'manager',
      password: 'manager-password',
    });

    await tour.next();

    expect(left).toEqual(['/api/oauth2/authorization/cognito?login_hint=manager']);
  });

  it('passes over signing in, both ways, for someone signed in', async () => {
    signedIn = manager;
    const tour = load();
    await tour.start();

    await tour.next();

    expect(tour.step()?.id).toBe('dashboard');
    expect(url).toBe('/dashboard');

    await tour.back();

    expect(tour.step()?.id).toBe('about');
    expect(url).toBe('/');
  });

  it('closes on its card, which stays on the page the visitor is on', async () => {
    signedIn = manager;
    sessionStorage.setItem('tour', JSON.stringify({ step: 'fix', copy: 41 }));
    const tour = load();
    await tour.resume();

    await tour.next();

    expect(tour.step()?.id).toBe('card');
    expect(url).toBe('/catalogs/41');
    expect(tour.number()).toBeNull();
  });

  it('sends someone who is not signed in to sign in, from a step that needs a session', async () => {
    sessionStorage.setItem('tour', JSON.stringify({ step: 'dashboard' }));
    const tour = load();

    await tour.resume();

    expect(tour.step()?.id).toBe('sign-in');
  });

  it('opens the step it was on when the page is loaded again, past signing in once signed in', async () => {
    const first = load();
    await first.start();
    await first.next();
    await first.next();
    signedIn = manager;

    const returned = load();
    await returned.resume();

    expect(returned.step()?.id).toBe('dashboard');
  });

  it("says a step can't be shown when its element has not appeared after 10 seconds", async () => {
    vi.useFakeTimers();
    const tour = load();
    await tour.start();

    await vi.advanceTimersByTimeAsync(9_900);
    expect(tour.missing()).toBe(false);

    await vi.advanceTimersByTimeAsync(100);
    expect(tour.missing()).toBe(true);
  });

  it('rings an element that appears while its step waits for it, and takes the ring off after', async () => {
    vi.useFakeTimers();
    const tour = load();
    await tour.start();

    const heading = onThePage('about');
    await vi.advanceTimersByTimeAsync(500);

    expect(tour.target()).toBe(heading);
    expect(heading.classList).toContain('tour-target');

    await tour.next();

    expect(heading.classList).not.toContain('tour-target');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(tour.target()).toBeNull();
  });

  describe('the offer', () => {
    /** A screen as wide as the tour needs, or narrower. */
    const screen = (wide: boolean) =>
      vi.stubGlobal('matchMedia', (query: string) => ({
        matches: query === WIDE && wide,
      }));

    afterEach(() => vi.unstubAllGlobals());

    it('is made on a wide screen, and not again in this browser once declined', () => {
      screen(true);
      const tour = load();

      tour.offer();
      expect(tour.offering()).toBe(true);

      tour.decline();
      expect(tour.offering()).toBe(false);

      const later = load();
      later.offer();
      expect(later.offering()).toBe(false);
    });

    it('is not made on a narrow screen, or while a tour is open', async () => {
      screen(false);
      const narrow = load();
      narrow.offer();
      expect(narrow.offering()).toBe(false);

      screen(true);
      const tour = load();
      await tour.start();
      tour.offer();
      expect(tour.offering()).toBe(false);

      // A page loaded again offers nothing while the tour it kept is still opening.
      const reloaded = load();
      reloaded.offer();
      expect(reloaded.offering()).toBe(false);
    });

    it('is not made again after a tour ends, which forgets its step, its ring, and gives focus back', async () => {
      screen(true);
      const button = document.createElement('button');
      document.body.append(button);
      button.focus();
      const heading = onThePage('about');
      const tour = load();
      await tour.start();

      tour.end();

      expect(tour.step()).toBeNull();
      expect(sessionStorage.getItem('tour')).toBeNull();
      expect(heading.classList).not.toContain('tour-target');
      expect(document.activeElement).toBe(button);
      tour.offer();
      expect(tour.offering()).toBe(false);
    });

    it("gives focus to the page's heading when what had it before is gone", async () => {
      const heading = document.createElement('h1');
      heading.tabIndex = -1;
      document.body.append(heading);
      const offered = document.createElement('button');
      document.body.append(offered);
      offered.focus();
      const tour = load();
      await tour.start();
      offered.remove();

      tour.end();

      expect(document.activeElement).toBe(heading);
    });
  });

  describe('the working copy', () => {
    /** A tour signed in as the manager, on the step given, as a reload would find it. */
    async function on(step: string, kept: object = {}): Promise<Tour> {
      signedIn = manager;
      sessionStorage.setItem('tour', JSON.stringify({ step, ...kept }));
      const tour = load();
      await tour.resume();
      return tour;
    }

    /** The cell the tour breaks, holding the availability given, with the keys pressed on it. */
    function theCell(holding: string): string[] {
      const cell = document.createElement('td');
      cell.dataset['cell'] = 'SEAT_HEATED_FRONT Luxury NA';
      cell.tabIndex = -1;
      cell.textContent = holding;
      const pressed: string[] = [];
      cell.addEventListener('keydown', (event) => pressed.push(event.key));
      document.body.append(cell);
      return pressed;
    }

    it('opens the current Approved Sedan 2027 from the dashboard, found by its line and year', async () => {
      const tour = await on('dashboard');

      await tour.next();

      expect(tour.step()?.id).toBe('approved');
      expect(url).toBe('/approved/7');
    });

    it('makes a working copy named for the time to the second, opens it, and keeps its id', async () => {
      const tour = await on('copy');

      await tour.next();

      expect(made).toEqual([
        {
          name: expect.stringMatching(/^Tour copy \d\d:\d\d:\d\d$/),
          vehicleLineId: 5,
          modelYear: 2027,
        },
      ]);
      expect(url).toBe('/catalogs/41');
      expect(tour.step()?.id).toBe('break');
      expect(JSON.parse(sessionStorage.getItem('tour')!)).toMatchObject({ copy: 41 });
    });

    it('takes a copy the visitor made themselves on that step as the one it made', async () => {
      const tour = await on('copy');

      navigations.next(new NavigationEnd(2, '/catalogs/52', '/catalogs/52'));
      await vi.waitFor(() => expect(tour.step()?.id).toBe('break'));

      expect(made).toEqual([]);
      expect(JSON.parse(sessionStorage.getItem('tour')!)).toMatchObject({ copy: 52 });
    });

    it('asks for its cell to be shown, sets it to not offered, and then back to what it held', async () => {
      const pressed = theCell('S');
      const tour = await on('break', { copy: 41 });
      expect(url).toBe('/catalogs/41');
      expect(tour.cell()).toEqual({ feature: 'SEAT_HEATED_FRONT', trim: 'Luxury', region: 'NA' });

      await tour.next();
      expect(pressed).toEqual(['-']);
      expect(tour.step()?.id).toBe('fix');

      await tour.next();
      expect(pressed).toEqual(['-', 's']);
      expect(tour.step()?.id).toBe('card');
    });

    it('deletes the copy it made once, with its current revision, and leaves its page', async () => {
      const tour = await on('fix', { copy: 41, held: 'S' });

      tour.end();
      tour.end();

      await vi.waitFor(() => expect(deleted).toEqual([[41, 9]]));
      await vi.waitFor(() => expect(url).toBe('/dashboard'));
    });
  });
});
