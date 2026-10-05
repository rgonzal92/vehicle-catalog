import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NAVIGATE, SessionService } from './session.service';

describe('SessionService', () => {
  let session: SessionService;
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
    session = TestBed.inject(SessionService);
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

  it('signs out, then sends the browser to the address the backend names', async () => {
    const signingOut = session.signOut();
    const request = backend.expectOne('/api/logout');
    expect(request.request.method).toBe('POST');
    request.flush({ logoutUrl: 'https://login.example.test/logout?client_id=catalog' });
    await signingOut;

    expect(navigate).toHaveBeenCalledWith('https://login.example.test/logout?client_id=catalog');
  });
});
