import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { LandingPage } from './landing-page';

describe('LandingPage', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  it('shows the product name and a one-line description', async () => {
    const fixture = TestBed.createComponent(LandingPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('h1')?.textContent).toContain('Vehicle Catalog');
    expect(page.querySelector('p')?.textContent).toContain('each trim of a vehicle line');
  });

  it('offers sign-in through the backend', async () => {
    const fixture = TestBed.createComponent(LandingPage);
    await fixture.whenStable();
    const signIn = (fixture.nativeElement as HTMLElement).querySelector('a');

    expect(signIn?.textContent).toContain('Sign in');
    expect(signIn?.getAttribute('href')).toBe('/api/oauth2/authorization/cognito');
  });

  it('lists the demo accounts the backend publishes', async () => {
    const fixture = TestBed.createComponent(LandingPage);
    fixture.detectChanges();
    backend.expectOne('/api/demo-accounts').flush([
      { role: 'author', username: 'demo-author', password: 'demo-password' },
      { role: 'admin', username: 'demo-admin', password: null },
    ]);
    await fixture.whenStable();
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');

    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('author');
    expect(rows[0].textContent).toContain('demo-author');
    expect(rows[0].textContent).toContain('demo-password');
    expect(rows[1].textContent).toContain('No password');
  });

  it('says that visitor work is deleted every day, whoever owns it, and that the accounts stay', async () => {
    const fixture = TestBed.createComponent(LandingPage);
    fixture.detectChanges();
    backend.expectOne('/api/demo-accounts').flush([]);
    await fixture.whenStable();
    const notice = (fixture.nativeElement as HTMLElement).querySelector('[data-reset-notice]');

    expect(notice?.textContent).toContain('deleted every day at 03:00 UTC');
    expect(notice?.textContent?.replace(/\s+/g, ' ')).toContain(
      'including what the demo accounts own, their notifications, and the spreadsheets they exported.',
    );
    expect(notice?.textContent).toContain(
      'The accounts themselves stay, so you can sign in again.',
    );
  });
});
