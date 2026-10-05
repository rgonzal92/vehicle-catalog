import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Role, Session } from '../../core/session';
import { DashboardPage } from './dashboard-page';

describe('DashboardPage', () => {
  /** Renders the dashboard for a person holding these roles and returns the page. */
  async function dashboardFor(roles: Role[]): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const loading = TestBed.inject(Session).load();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/me')
      .flush({ id: 7, name: 'Maya', email: null, roles });
    await loading;

    const fixture = TestBed.createComponent(DashboardPage);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const headings = (page: HTMLElement) =>
    Array.from(page.querySelectorAll('h2')).map((heading) => heading.textContent?.trim());

  it('names the person and their role', async () => {
    const page = await dashboardFor(['manager', 'author']);

    expect(page.textContent).toContain('Maya');
    expect(page.querySelector('[data-role]')?.textContent).toContain('manager');
  });

  it('shows an author their catalogs and the Approved catalogs', async () => {
    expect(headings(await dashboardFor(['author']))).toEqual(['My catalogs', 'Approved catalogs']);
  });

  it('adds the review queue for a manager', async () => {
    expect(headings(await dashboardFor(['manager', 'author']))).toEqual([
      'My catalogs',
      'Approved catalogs',
      'Review queue',
    ]);
  });

  it('adds the admin links for an admin', async () => {
    expect(headings(await dashboardFor(['admin', 'manager', 'author']))).toEqual([
      'My catalogs',
      'Approved catalogs',
      'Review queue',
      'Admin',
    ]);
  });
});
