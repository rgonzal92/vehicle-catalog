import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NAVIGATE, Role, Session } from './session';
import { Shell } from './shell';

@Component({ template: '<p>The page</p>' })
class Page {}

describe('Shell', () => {
  let backend: HttpTestingController;
  let harness: RouterTestingHarness;

  /** Opens an address inside the frame as a person with these roles. */
  async function open(url: string, roles: Role[] = ['author']): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: '',
            component: Shell,
            children: [
              { path: 'dashboard', title: 'Dashboard · Vehicle Catalog', component: Page },
              { path: 'catalogs/:id', title: 'Catalog · Vehicle Catalog', component: Page },
              { path: 'analyst', title: 'Analyst · Vehicle Catalog', component: Page },
              { path: 'admin/users', title: 'Users · Vehicle Catalog', component: Page },
            ],
          },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NAVIGATE, useValue: () => undefined },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    const loading = TestBed.inject(Session).load();
    backend.expectOne('/api/me').flush({ id: 7, name: 'Maya', email: 'maya@example.com', roles });
    await loading;

    harness = await RouterTestingHarness.create(url);
    return harness.routeNativeElement!.closest('app-shell') as HTMLElement;
  }

  const links = (frame: HTMLElement) =>
    Array.from(frame.querySelectorAll('[role="navigation"] a')).map((link) =>
      link.textContent?.trim(),
    );

  const current = (frame: HTMLElement) =>
    frame.querySelector('[role="navigation"] a[aria-current="page"]')?.textContent?.trim();

  const button = (frame: HTMLElement, label: string) =>
    frame.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;

  beforeEach(() => {
    // The sidebar watches its own size, which the test page cannot tell it.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.documentElement.classList.remove('app-dark');
    localStorage.clear();
  });

  it('shows the page inside it, under the title of its route', async () => {
    const frame = await open('/dashboard');

    expect(frame.querySelector('h1')?.textContent?.trim()).toBe('Dashboard');
    expect(frame.querySelector('main')?.textContent).toContain('The page');
  });

  it('names the person and their role', async () => {
    const frame = await open('/dashboard', ['manager', 'author']);

    expect(frame.querySelector('header')?.textContent).toContain('Maya');
    expect(frame.querySelector('[data-role]')?.textContent?.trim()).toBe('manager');
  });

  it('offers an author the dashboard alone', async () => {
    expect(links(await open('/dashboard'))).toEqual(['Dashboard', 'Analyst']);
  });

  it('offers an admin the admin pages too', async () => {
    expect(links(await open('/dashboard', ['admin', 'manager', 'author']))).toEqual([
      'Dashboard',
      'Analyst',
      'Vehicle lines',
      'Trims',
      'Regions',
      'Feature library',
      'Global rules',
      'Users',
      'Jobs',
    ]);
  });

  it('marks the link of the page that is open', async () => {
    const frame = await open('/admin/users', ['admin', 'manager', 'author']);

    expect(current(frame)).toBe('Users');
  });

  it('marks the dashboard on a page that is reached from it', async () => {
    expect(current(await open('/catalogs/41'))).toBe('Dashboard');
  });

  it('marks the analyst on its own page, and the dashboard no more', async () => {
    expect(current(await open('/analyst'))).toBe('Analyst');
  });

  it('takes the title of each page that is opened, and moves focus to it', async () => {
    const frame = await open('/dashboard', ['admin', 'manager', 'author']);

    await harness.navigateByUrl('/admin/users');

    const title = frame.querySelector('h1')!;
    await vi.waitFor(() => expect(title.textContent?.trim()).toBe('Users'));
    await vi.waitFor(() => expect(document.activeElement).toBe(title));
    expect(current(frame)).toBe('Users');
  });

  it('leaves focus where it is when the frame first appears', async () => {
    const frame = await open('/dashboard');
    await harness.fixture.whenStable();

    expect(document.activeElement).not.toBe(frame.querySelector('h1'));
  });

  it('switches between light and dark', async () => {
    const frame = await open('/dashboard');

    button(frame, 'Use dark colors').click();

    await vi.waitFor(() => expect(button(frame, 'Use light colors')).not.toBeNull());
    expect(document.documentElement.classList.contains('app-dark')).toBe(true);
  });

  it('collapses the sidebar to its icons, keeps the names of its links, and remembers', async () => {
    const frame = await open('/dashboard');

    button(frame, 'Collapse the sidebar').click();

    await vi.waitFor(() => expect(button(frame, 'Expand the sidebar')).not.toBeNull());
    expect(links(frame)).toEqual(['Dashboard', 'Analyst']);
    expect(localStorage.getItem('sidebar')).toBe('collapsed');
  });

  it('starts with the sidebar as the person left it', async () => {
    localStorage.setItem('sidebar', 'collapsed');

    const frame = await open('/dashboard');

    expect(button(frame, 'Expand the sidebar')).not.toBeNull();
  });

  it('starts with the sidebar collapsed on a narrow screen', async () => {
    vi.stubGlobal('innerWidth', 800);

    const frame = await open('/dashboard');

    expect(button(frame, 'Expand the sidebar')).not.toBeNull();
  });

  it('signs the person out from their account', async () => {
    const frame = await open('/dashboard');
    const account = Array.from(frame.querySelectorAll('button')).find((candidate) =>
      candidate.textContent?.includes('Maya'),
    )!;
    expect(account.textContent?.replace(/\s+/g, ' ').trim()).toBe('Account: Maya');

    account.click();
    const signOut = await vi.waitFor(() => {
      const found = Array.from(document.querySelectorAll('button')).find(
        (candidate) => candidate.textContent?.trim() === 'Sign out',
      );
      expect(found).toBeDefined();
      return found!;
    });
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('maya@example.com');
    signOut.click();

    await vi.waitFor(() => backend.expectOne({ method: 'POST', url: '/api/logout' }).flush(null));
  });

  it('moves focus to the page when the skip link is followed', async () => {
    const frame = await open('/dashboard');
    const skip = frame.querySelector('a')!;
    expect(skip.textContent?.trim()).toBe('Skip to content');

    skip.click();

    expect(document.activeElement).toBe(frame.querySelector('main'));
  });
});
