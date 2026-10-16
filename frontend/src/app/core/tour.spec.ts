import { HttpClient } from '@angular/common/http';
import { Injector } from '@angular/core';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { NAVIGATE, Person, Session } from './session';
import { Tour, WIDE } from './tour';

describe('Tour', () => {
  /** Who the backend says is signed in: nobody, until a test signs someone in. */
  let signedIn: Person | null;

  /** The page the app shows, as the router has it. */
  let url: string;

  /** Addresses the browser was sent to, outside the app. */
  let left: string[];

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
            navigateByUrl: async (to: string) => {
              url = to;
              return true;
            },
          },
        },
        { provide: NAVIGATE, useValue: (to: string) => left.push(to) },
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
    const tour = load();
    await tour.start();
    await tour.next();

    await tour.next();

    expect(tour.step()?.id).toBe('card');
    expect(url).toBe('/dashboard');
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
});
