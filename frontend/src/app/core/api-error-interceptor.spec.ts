import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { apiErrorInterceptor } from './api-error-interceptor';
import { Session } from './session';

describe('apiErrorInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let messages: MessageService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
        MessageService,
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    messages = TestBed.inject(MessageService);
  });

  it('tells the person when the server fails', () => {
    const shown = vi.spyOn(messages, 'add');

    http.get('/api/anything').subscribe({ error: () => undefined });
    backend.expectOne('/api/anything').flush(null, { status: 500, statusText: 'Server Error' });

    expect(shown).toHaveBeenCalledWith(expect.objectContaining({ severity: 'error' }));
  });

  it('leaves a refusal the caller can explain to the caller', () => {
    const shown = vi.spyOn(messages, 'add');

    http.get('/api/anything').subscribe({ error: () => undefined });
    backend.expectOne('/api/anything').flush(null, { status: 409, statusText: 'Conflict' });

    expect(shown).not.toHaveBeenCalled();
  });

  it('forgets the person and returns to the landing page when the session has ended', async () => {
    const session = TestBed.inject(Session);
    const loading = session.load();
    backend.expectOne('/api/me').flush({ id: 7, name: 'Maya', email: null, roles: [] });
    await loading;
    const navigated = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');

    http.get('/api/anything').subscribe({ error: () => undefined });
    backend.expectOne('/api/anything').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(session.person()).toBeNull();
    expect(navigated).toHaveBeenCalledWith('/');
  });
});
