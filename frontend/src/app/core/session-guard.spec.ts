import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { anyone, holding, withoutRole } from './session-guard';

@Component({ template: '' })
class Page {}

describe('session guards', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', canActivate: [anyone], component: Page },
          { path: 'dashboard', canActivate: [holding('author')], component: Page },
          { path: 'admin-only', canActivate: [holding('admin')], component: Page },
          { path: 'no-role', canActivate: [withoutRole], component: Page },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  /** Opens an address as a person with these roles (or as a visitor) and says where they end up. */
  async function open(url: string, roles: string[] | 'visitor'): Promise<string> {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl(url);
    const request = await vi.waitFor(() => backend.expectOne('/api/me'));
    if (roles === 'visitor') {
      request.flush(null, { status: 401, statusText: 'Unauthorized' });
    } else {
      request.flush({ id: 7, name: 'Maya', email: null, roles });
    }
    await navigation;

    return TestBed.inject(Router).url;
  }

  it('lets a visitor see the landing page and nothing that needs a session', async () => {
    expect(await open('/', 'visitor')).toBe('/');
  });

  it('leads a visitor away from the dashboard', async () => {
    expect(await open('/dashboard', 'visitor')).toBe('/');
  });

  it('lets a person open a route for a role they hold', async () => {
    expect(await open('/admin-only', ['admin', 'manager', 'author'])).toBe('/admin-only');
  });

  it('leads a person to the dashboard from a route for a role they lack', async () => {
    expect(await open('/admin-only', ['manager', 'author'])).toBe('/dashboard');
  });

  it('shows a person without a role the no-role page whatever they open', async () => {
    expect(await open('/dashboard', [])).toBe('/no-role');
  });

  it('shows a person without a role the no-role page in place of the landing page', async () => {
    expect(await open('/', [])).toBe('/no-role');
  });

  it('keeps the no-role page for people without a role', async () => {
    expect(await open('/no-role', ['author'])).toBe('/dashboard');
  });

  it('leads a visitor away from the no-role page', async () => {
    expect(await open('/no-role', 'visitor')).toBe('/');
  });
});
