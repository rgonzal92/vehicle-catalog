import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { UsersPage } from './users-page';

describe('UsersPage', () => {
  let backend: HttpTestingController;
  let fixture: ComponentFixture<UsersPage>;

  const users = [
    {
      username: 'demo-author',
      email: null,
      role: 'author',
      lastLogin: '2026-10-05T14:30:00Z',
      changeable: false,
    },
    {
      username: 'visitor',
      email: 'visitor@example.test',
      role: 'author',
      lastLogin: null,
      changeable: true,
    },
    {
      username: 'newcomer',
      email: 'new@example.test',
      role: null,
      lastLogin: null,
      changeable: true,
    },
  ];

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');
  const dialog = () => document.querySelector<HTMLElement>('[role="dialog"]');
  const button = (name: string) =>
    Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find(
      (candidate) =>
        candidate.getAttribute('aria-label') === name || candidate.textContent?.trim() === name,
    );

  async function page(): Promise<HTMLElement> {
    // A dropdown asks how wide the screen is before it opens, which the test page cannot say.
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UsersPage);
    fixture.detectChanges();
    backend.expectOne('/api/admin/users').flush(users);
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => expect(rows(element).length).toBe(users.length));
    await fixture.whenStable();

    return element;
  }

  /** Opens the dialog for the account and picks the role in its dropdown. */
  async function pick(username: string, role: string): Promise<void> {
    button(`Change role of ${username}`)?.click();
    await vi.waitFor(() => expect(dialog()).not.toBeNull());
    dialog()!.querySelector<HTMLElement>('p-select')!.click();
    const option = await vi.waitFor(() => {
      const found = Array.from(document.querySelectorAll<HTMLElement>('[role="option"]')).find(
        (candidate) => candidate.textContent?.trim() === role,
      );
      expect(found).toBeDefined();
      return found!;
    });
    option.click();
    await fixture.whenStable();
  }

  afterEach(() => {
    backend.verify();
    vi.unstubAllGlobals();
  });

  it('lists each account with its email, its role, and when its person last signed in', async () => {
    const [demo, visitor, newcomer] = rows(await page());

    expect(demo).toContain('demo-author');
    expect(demo).toContain('Author');
    expect(demo).toContain('2026');
    expect(visitor).toContain('visitor@example.test');
    expect(visitor).toContain('Never');
    expect(newcomer).toContain('No role');
  });

  it('offers a role change only for an account whose role can be changed', async () => {
    await page();

    expect(button('Change role of visitor')).toBeDefined();
    expect(button('Change role of newcomer')).toBeDefined();
    expect(button('Change role of demo-author')).toBeUndefined();
  });

  it('changes a role and shows the list as the backend has it afterwards', async () => {
    const element = await page();

    await pick('visitor', 'Manager');
    button('Save')?.click();
    const request = backend.expectOne({ method: 'PUT', url: '/api/admin/users/visitor/role' });
    expect(request.request.body).toEqual({ role: 'manager' });
    request.flush({ ...users[1], role: 'manager' });
    const reread = await vi.waitFor(() =>
      backend.expectOne({ method: 'GET', url: '/api/admin/users' }),
    );
    reread.flush([users[0], { ...users[1], role: 'manager' }, users[2]]);

    await vi.waitFor(() => expect(rows(element)[1]).toContain('Manager'));
    await vi.waitFor(() => expect(dialog()).toBeNull());
  });

  it('keeps the dialog open with the reason when the change is refused', async () => {
    const element = await page();

    await pick('visitor', 'Admin');
    button('Save')?.click();
    backend
      .expectOne({ method: 'PUT', url: '/api/admin/users/visitor/role' })
      .flush(
        { code: 'PROTECTED_ACCOUNT', detail: 'The role of this account cannot be changed.' },
        { status: 403, statusText: 'Forbidden' },
      );

    await vi.waitFor(() =>
      expect(dialog()?.textContent).toContain('The role of this account cannot be changed.'),
    );
    expect(rows(element)[1]).toContain('Author');
  });

  it('cannot save before a role is picked for an account that has none', async () => {
    await page();

    button('Change role of newcomer')?.click();
    await vi.waitFor(() => expect(dialog()).not.toBeNull());
    await fixture.whenStable();

    expect(button('Save')?.disabled).toBe(true);
  });

  it('cannot save the role an account already has, which would only sign its person out', async () => {
    await page();

    button('Change role of visitor')?.click();
    await vi.waitFor(() => expect(dialog()).not.toBeNull());
    await fixture.whenStable();
    expect(dialog()?.textContent).toContain('Saving signs visitor out of the app.');
    expect(button('Save')?.disabled).toBe(true);

    await pick('visitor', 'Manager');
    expect(button('Save')?.disabled).toBe(false);
  });

  it('sends one change however often Save is pressed', async () => {
    await page();

    await pick('visitor', 'Manager');
    button('Save')?.click();
    button('Save')?.click();

    backend.expectOne({ method: 'PUT', url: '/api/admin/users/visitor/role' }).flush(users[1]);
    await vi
      .waitFor(() => backend.expectOne({ method: 'GET', url: '/api/admin/users' }))
      .then((reread) => reread.flush(users));
  });

  it('closes the dialog once the role is changed, even when the list cannot be read again', async () => {
    const element = await page();

    await pick('visitor', 'Manager');
    button('Save')?.click();
    backend
      .expectOne({ method: 'PUT', url: '/api/admin/users/visitor/role' })
      .flush({ ...users[1], role: 'manager' });
    const reread = await vi.waitFor(() =>
      backend.expectOne({ method: 'GET', url: '/api/admin/users' }),
    );
    reread.flush(null, { status: 401, statusText: 'Unauthorized' });

    await vi.waitFor(() => expect(dialog()).toBeNull());
    expect(rows(element).length).toBe(users.length);
  });

  it('leaves the dialog of another account alone when an earlier change is answered late', async () => {
    await page();

    await pick('visitor', 'Manager');
    button('Save')?.click();
    const earlier = backend.expectOne({ method: 'PUT', url: '/api/admin/users/visitor/role' });
    button('Cancel')?.click();
    await vi.waitFor(() => expect(dialog()).toBeNull());
    button('Change role of newcomer')?.click();
    await vi.waitFor(() => expect(dialog()?.textContent).toContain('newcomer'));

    earlier.flush(
      { code: 'NOT_FOUND', detail: 'There is nothing at this address.' },
      { status: 404, statusText: 'Not Found' },
    );
    // The answer is taken in once the page has had its turn, which a timer is the first thing after.
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    expect(dialog()).not.toBeNull();
    expect(dialog()?.textContent).not.toContain('There is nothing at this address.');
  });

  it('asks for an account by a name that is safe in an address', async () => {
    const odd = { ...users[1], username: 'a/b c' };
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UsersPage);
    fixture.detectChanges();
    backend.expectOne('/api/admin/users').flush([odd]);
    await vi.waitFor(() => expect(button('Change role of a/b c')).toBeDefined());

    await pick('a/b c', 'Manager');
    button('Save')?.click();

    backend.expectOne({ method: 'PUT', url: '/api/admin/users/a%2Fb%20c/role' }).flush(odd);
    await vi
      .waitFor(() => backend.expectOne({ method: 'GET', url: '/api/admin/users' }))
      .then((reread) => reread.flush([odd]));
  });
});
