import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';

describe('routes', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  /** The guard asks who is signed in a moment after the navigation starts. */
  const sessionRequest = () => vi.waitFor(() => backend.expectOne('/api/me'));

  it('shows the landing page at /', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/');
    (await sessionRequest()).flush(null, { status: 401, statusText: 'Unauthorized' });
    await navigation;

    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'Vehicle Catalog',
    );
  });

  it('shows the dashboard to a signed-in person', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/dashboard');
    (await sessionRequest()).flush({ id: 7, name: 'Maya', email: null, roles: ['author'] });
    await navigation;

    expect(TestBed.inject(Router).url).toBe('/dashboard');
    expect(harness.routeNativeElement?.textContent).toContain('Maya');
  });

  it('leads a visitor without a session from the dashboard to the landing page', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/dashboard');
    (await sessionRequest()).flush(null, { status: 401, statusText: 'Unauthorized' });
    await navigation;

    expect(TestBed.inject(Router).url).toBe('/');
  });

  it('shows a person without a role the no-role page in place of the landing page', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/');
    (await sessionRequest()).flush({ id: 7, name: 'Maya', email: null, roles: [] });
    await navigation;

    expect(TestBed.inject(Router).url).toBe('/no-role');
  });

  it('leads an address that does not exist to where the person belongs', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/no-such-page');
    (await sessionRequest()).flush({ id: 7, name: 'Maya', email: null, roles: [] });
    await navigation;

    expect(TestBed.inject(Router).url).toBe('/no-role');
  });

  it('shows a signed-in person without a role the no-role page', async () => {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/dashboard');
    (await sessionRequest()).flush({ id: 7, name: 'Maya', email: null, roles: [] });
    await navigation;

    expect(TestBed.inject(Router).url).toBe('/no-role');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'No role assigned',
    );
  });
});
