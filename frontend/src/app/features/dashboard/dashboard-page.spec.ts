import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Role, Session } from '../../core/session';
import { DashboardPage } from './dashboard-page';

describe('DashboardPage', () => {
  const compactSuv = {
    id: 3,
    vehicleLine: 'Compact SUV',
    modelYear: 2026,
    catalogId: 12,
    versionNumber: 2,
    approvedBy: 'Demo Manager',
    approvedAt: '2025-11-03T15:30:00Z',
  };

  /**
   * Renders the dashboard for a person holding these roles, with these lineages having an Approved
   * version, and returns the page.
   */
  async function dashboardFor(roles: Role[], lineages: object[] = []): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const backend = TestBed.inject(HttpTestingController);
    const loading = TestBed.inject(Session).load();
    backend.expectOne('/api/me').flush({ id: 7, name: 'Maya', email: null, roles });
    await loading;

    const fixture = TestBed.createComponent(DashboardPage);
    backend.expectOne('/api/lineages').flush(lineages);
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

  it('lists each lineage that has an Approved version, with a way to open it', async () => {
    const page = await dashboardFor(['author'], [compactSuv]);

    const row = await vi.waitFor(() => {
      const found = page.querySelector('section[aria-labelledby="approved-catalogs"] tbody tr');
      expect(found).not.toBeNull();
      return found!;
    });
    const cells = Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent?.trim());
    expect(cells.slice(0, 3)).toEqual(['Compact SUV', '2026', '2']);
    expect(cells[3]).toContain('2025');
    expect(cells[4]).toBe('Demo Manager');
    const link = row.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/approved/3');
    expect(link?.getAttribute('aria-label')).toBe('Open Compact SUV 2026');
  });

  it('says so when no lineage has an Approved version', async () => {
    const page = await dashboardFor(['author']);

    expect(
      page.querySelector('section[aria-labelledby="approved-catalogs"]')?.textContent,
    ).toContain('There are no Approved catalogs.');
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
      'Admin links',
    ]);
  });
});
