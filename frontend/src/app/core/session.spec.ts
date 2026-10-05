import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NAVIGATE, Session } from './session';

describe('Session', () => {
  let session: Session;
  let backend: HttpTestingController;
  const navigate = vi.fn();

  beforeEach(() => {
    navigate.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NAVIGATE, useValue: navigate },
      ],
    });
    session = TestBed.inject(Session);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('knows the signed-in person after asking the backend once', async () => {
    const maya = { id: 7, name: 'Maya', email: 'maya@example.test', roles: [] };

    const first = session.load();
    backend.expectOne('/api/me').flush(maya);

    expect(await first).toEqual(maya);
    expect(session.person()).toEqual(maya);
    expect(await session.load()).toEqual(maya);
  });

  it('has no person when there is no session', async () => {
    const loading = session.load();
    backend
      .expectOne('/api/me')
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });

    expect(await loading).toBeNull();
    expect(session.person()).toBeNull();
  });

  it('has no person when the backend fails, and asks again the next time', async () => {
    const failing = session.load();
    backend.expectOne('/api/me').flush(null, { status: 503, statusText: 'Service Unavailable' });
    expect(await failing).toBeNull();

    const retry = session.load();
    backend.expectOne('/api/me').flush({ id: 7, name: 'Maya', email: null, roles: [] });
    expect((await retry)?.name).toBe('Maya');
  });

  it('sends the CSRF token from its cookie with a write', async () => {
    document.cookie = 'XSRF-TOKEN=token-from-cookie';

    const signingOut = session.signOut();
    const request = backend.expectOne('/api/logout');
    request.flush({ logoutUrl: '/' });
    await signingOut;

    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('token-from-cookie');
  });

  it('stays put when sign-out fails', async () => {
    const signingOut = session.signOut();
    backend
      .expectOne('/api/logout')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await signingOut;

    expect(navigate).not.toHaveBeenCalled();
  });

  it('signs out, then sends the browser to the address the backend names', async () => {
    const signingOut = session.signOut();
    const request = backend.expectOne('/api/logout');
    expect(request.request.method).toBe('POST');
    request.flush({ logoutUrl: 'https://login.example.test/logout?client_id=catalog' });
    await signingOut;

    expect(navigate).toHaveBeenCalledWith('https://login.example.test/logout?client_id=catalog');
  });
});
