import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
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
  async function dashboardFor(
    roles: Role[],
    lineages: object[] = [],
    mine: object[] = [],
  ): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    const loading = TestBed.inject(Session).load();
    backend.expectOne('/api/me').flush({ id: 7, name: 'Maya', email: null, roles });
    await loading;

    const fixture = TestBed.createComponent(DashboardPage);
    backend.expectOne('/api/catalogs?scope=mine').flush(mine);
    backend.expectOne('/api/lineages').flush(lineages);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const firstRow = (page: HTMLElement, section: string) =>
    vi.waitFor(() => {
      const found = page.querySelector(`section[aria-labelledby="${section}"] tbody tr`);
      expect(found).not.toBeNull();
      return found!;
    });

  const headings = (page: HTMLElement) =>
    Array.from(page.querySelectorAll('h2')).map((heading) => heading.textContent?.trim());

  it('shows an author their catalogs and the Approved catalogs', async () => {
    expect(headings(await dashboardFor(['author']))).toEqual(['My catalogs', 'Approved catalogs']);
  });

  it('lists each lineage that has an Approved version, with a way to open it', async () => {
    const page = await dashboardFor(['author'], [compactSuv]);

    const row = await firstRow(page, 'approved-catalogs');
    const cells = Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent?.trim());
    expect(cells.slice(0, 3)).toEqual(['Compact SUV', '2026', '2']);
    expect(cells[3]).toContain('2025');
    expect(cells[4]).toBe('Demo Manager');
    const link = row.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/approved/3');
    expect(link?.getAttribute('aria-label')).toBe('Open Compact SUV 2026');
  });

  it("lists the person's working copies, each with a way to open it", async () => {
    const page = await dashboardFor(
      ['author'],
      [],
      [
        {
          id: 41,
          name: 'Winter update',
          vehicleLine: 'Compact SUV',
          modelYear: 2027,
          status: 'DRAFT',
          updatedAt: '2026-01-09T10:00:00Z',
        },
      ],
    );

    const row = await firstRow(page, 'my-catalogs');
    const cells = Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent?.trim());
    expect(cells.slice(0, 4)).toEqual(['Winter update', 'Compact SUV', '2027', 'Draft']);
    expect(cells[4]).toContain('2026');
    const link = row.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/catalogs/41');
    expect(link?.getAttribute('aria-label')).toBe('Open Winter update');
  });

  describe('deleting a working copy', () => {
    const winterUpdate = {
      id: 41,
      name: 'Winter update',
      vehicleLine: 'Compact SUV',
      modelYear: 2027,
      status: 'DRAFT',
      revision: 6,
      updatedAt: '2026-01-09T10:00:00Z',
    };
    const submitted = { ...winterUpdate, id: 42, name: 'Sent for review', status: 'SUBMITTED' };

    const dialog = () => document.querySelector<HTMLElement>('.p-dialog');

    const dialogButton = (label: string) =>
      Array.from(dialog()?.querySelectorAll('button') ?? []).find(
        (candidate) => candidate.textContent?.trim() === label,
      )!;

    /** Renders the dashboard with the two working copies and asks to delete the one in Draft. */
    async function ask(): Promise<HTMLElement> {
      const page = await dashboardFor(['author'], [], [winterUpdate, submitted]);
      await firstRow(page, 'my-catalogs');
      page.querySelector<HTMLButtonElement>('button[aria-label="Delete Winter update"]')!.click();
      await vi.waitFor(() =>
        expect(dialog()?.querySelector('[data-question]')?.textContent).toContain(
          'Delete Winter update?',
        ),
      );

      return page;
    }

    const backend = () => TestBed.inject(HttpTestingController);

    it('is offered only for a working copy in status Draft', async () => {
      const page = await dashboardFor(['author'], [], [winterUpdate, submitted]);
      await firstRow(page, 'my-catalogs');

      expect(page.querySelector('button[aria-label="Delete Winter update"]')).not.toBeNull();
      expect(page.querySelector('button[aria-label="Delete Sent for review"]')).toBeNull();
    });

    it('asks first, and keeps the working copy when told to', async () => {
      const page = await ask();

      dialogButton('Keep').click();

      await vi.waitFor(() => expect(dialog()).toBeNull());
      backend().expectNone((request) => request.method === 'DELETE');
      // The focus goes back to the button that asked.
      await vi.waitFor(() =>
        expect(document.activeElement).toBe(
          page.querySelector('button[aria-label="Delete Winter update"]'),
        ),
      );
    });

    it('deletes it as the list shows it, and reads the list again', async () => {
      const page = await ask();

      dialogButton('Delete').click();

      const deletion = backend().expectOne({ method: 'DELETE', url: '/api/catalogs/41' });
      expect(deletion.request.headers.get('If-Match')).toBe('"6"');
      deletion.flush(null, { status: 204, statusText: 'No Content' });
      (await vi.waitFor(() => backend().expectOne('/api/catalogs?scope=mine'))).flush([submitted]);
      await vi.waitFor(() => expect(dialog()).toBeNull());
      expect(
        page.querySelector('section[aria-labelledby="my-catalogs"]')?.textContent,
      ).not.toContain('Winter update');
    });

    it('deletes it once, however often Delete is pressed, and then puts the focus on the heading of the list', async () => {
      const page = await ask();

      dialogButton('Delete').click();
      dialogButton('Delete').click();

      backend()
        .expectOne({ method: 'DELETE', url: '/api/catalogs/41' })
        .flush(null, { status: 204, statusText: 'No Content' });
      (await vi.waitFor(() => backend().expectOne('/api/catalogs?scope=mine'))).flush([submitted]);
      await vi.waitFor(() => expect(dialog()).toBeNull());
      backend().expectNone({ method: 'DELETE', url: '/api/catalogs/41' });
      await vi.waitFor(() =>
        expect(document.activeElement).toBe(page.querySelector('#my-catalogs')),
      );
    });

    it('closes the question and gives the reason when the working copy has left status Draft', async () => {
      const page = await ask();
      const shown = vi.spyOn(TestBed.inject(MessageService), 'add');

      dialogButton('Delete').click();
      backend()
        .expectOne({ method: 'DELETE', url: '/api/catalogs/41' })
        .flush(
          { code: 'NOT_DRAFT', detail: 'Only a catalog in status Draft can be edited.' },
          { status: 409, statusText: 'Conflict' },
        );
      (await vi.waitFor(() => backend().expectOne('/api/catalogs?scope=mine'))).flush([
        { ...winterUpdate, status: 'SUBMITTED' },
        submitted,
      ]);

      await vi.waitFor(() => expect(dialog()).toBeNull());
      expect(shown).toHaveBeenCalledWith(
        expect.objectContaining({
          summary: 'Not deleted',
          detail: 'Only a catalog in status Draft can be edited.',
        }),
      );
      expect(page.querySelector('button[aria-label="Delete Winter update"]')).toBeNull();
    });

    it('keeps the question open with the reason when the backend refuses, about the list as it then is', async () => {
      await ask();

      dialogButton('Delete').click();
      backend()
        .expectOne({ method: 'DELETE', url: '/api/catalogs/41' })
        .flush(
          { code: 'REVISION_CONFLICT', detail: 'This catalog was changed somewhere else.' },
          { status: 412, statusText: 'Precondition Failed' },
        );
      (await vi.waitFor(() => backend().expectOne('/api/catalogs?scope=mine'))).flush([
        { ...winterUpdate, revision: 7 },
        submitted,
      ]);

      await vi.waitFor(() =>
        expect(dialog()?.textContent).toContain(
          'This working copy was changed after the list was read. The list shows it as it is now.',
        ),
      );
      dialogButton('Delete').click();
      const again = backend().expectOne({ method: 'DELETE', url: '/api/catalogs/41' });
      expect(again.request.headers.get('If-Match')).toBe('"7"');
      again.flush(null, { status: 204, statusText: 'No Content' });
      (await vi.waitFor(() => backend().expectOne('/api/catalogs?scope=mine'))).flush([submitted]);
      await vi.waitFor(() => expect(dialog()).toBeNull());
    });
  });

  it('says so when the person has no working copies', async () => {
    const page = await dashboardFor(['author']);

    expect(page.querySelector('section[aria-labelledby="my-catalogs"]')?.textContent).toContain(
      'You have no catalogs.',
    );
  });

  it('opens the new catalog dialog from My catalogs', async () => {
    const page = await dashboardFor(['author']);

    Array.from(page.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'New catalog')
      ?.click();

    // The dialog asks for the vehicle lines and the model years as it opens.
    TestBed.inject(HttpTestingController).expectOne('/api/vehicle-lines').flush([]);
    TestBed.inject(HttpTestingController)
      .expectOne('/api/reference')
      .flush({ vehicleTypes: [], categories: [], modelYears: [] });
    await vi.waitFor(() =>
      expect(document.querySelector('.p-dialog')?.textContent).toContain('New catalog'),
    );
  });

  it('says so when no lineage has an Approved version', async () => {
    const page = await dashboardFor(['author']);

    expect(
      page.querySelector('section[aria-labelledby="approved-catalogs"]')?.textContent,
    ).toContain('There are no Approved catalogs.');
  });

  it('says nothing about Approved catalogs until the backend has answered', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    const loading = TestBed.inject(Session).load();
    backend.expectOne('/api/me').flush({ id: 7, name: 'Maya', email: null, roles: ['author'] });
    await loading;

    const fixture = TestBed.createComponent(DashboardPage);
    await fixture.whenStable();
    const section = (fixture.nativeElement as HTMLElement).querySelector(
      'section[aria-labelledby="approved-catalogs"]',
    );

    expect(section?.textContent?.trim()).toBe('Approved catalogs');
    backend.expectOne('/api/catalogs?scope=mine').flush([]);
    backend.expectOne('/api/lineages').flush([]);
  });

  it('adds the review queue for a manager', async () => {
    expect(headings(await dashboardFor(['manager', 'author']))).toEqual([
      'My catalogs',
      'Approved catalogs',
      'Review queue',
    ]);
  });

  /** The dashboard of an author, with neither of its lists read yet. */
  async function unanswered() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    const loading = TestBed.inject(Session).load();
    backend.expectOne('/api/me').flush({ id: 7, name: 'Maya', email: null, roles: ['author'] });
    await loading;

    const fixture = TestBed.createComponent(DashboardPage);
    await fixture.whenStable();
    return { backend, page: fixture.nativeElement as HTMLElement };
  }

  it('stands in for each list until it has been read', async () => {
    const { backend, page } = await unanswered();
    expect(page.querySelectorAll('app-loading')).toHaveLength(2);
    expect(page.querySelector('[role="alert"]')).toBeNull();

    backend.expectOne('/api/catalogs?scope=mine').flush([]);
    backend.expectOne('/api/lineages').flush([]);

    await vi.waitFor(() => expect(page.querySelectorAll('app-loading')).toHaveLength(0));
    expect(page.textContent).toContain('You have no catalogs.');
    expect(page.textContent).toContain('There are no Approved catalogs.');
  });

  it('says so when a list cannot be read, and reads it again when asked', async () => {
    const { backend, page } = await unanswered();
    backend.expectOne('/api/lineages').flush([]);
    backend
      .expectOne('/api/catalogs?scope=mine')
      .flush(null, { status: 500, statusText: 'Server Error' });

    const failed = await vi.waitFor(() => {
      const found = page.querySelector('section[aria-labelledby="my-catalogs"] [role="alert"]');
      expect(found?.textContent).toContain('Your catalogs could not be read.');
      return found!;
    });
    expect(page.querySelector('app-loading')).toBeNull();
    Array.from(failed.querySelectorAll('button'))
      .find((candidate) => candidate.textContent?.trim() === 'Try again')!
      .click();

    (await vi.waitFor(() => backend.expectOne('/api/catalogs?scope=mine'))).flush([]);
    await vi.waitFor(() => expect(page.textContent).toContain('You have no catalogs.'));
    expect(page.querySelector('section[aria-labelledby="my-catalogs"] [role="alert"]')).toBeNull();
  });
});
