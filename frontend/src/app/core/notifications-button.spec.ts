import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NotificationsButton } from './notifications-button';

@Component({ template: '<p>A page</p>' })
class Page {}

const approved = (id: number, catalog: string, read: boolean) => ({
  id,
  kind: 'CATALOG_APPROVED',
  payload: {
    catalogId: 40 + id,
    catalog,
    lineageId: 3,
    vehicleLine: 'Compact SUV',
    modelYear: 2026,
    versionNumber: id,
    reviewer: 'Mia Manager',
  },
  createdAt: '2026-10-09T10:00:00Z',
  read,
});

describe('NotificationsButton', () => {
  let backend: HttpTestingController;
  let fixture: ComponentFixture<NotificationsButton>;

  const button = () => fixture.nativeElement.querySelector('button') as HTMLButtonElement;
  const list = () => document.querySelector<HTMLElement>('.p-popover');
  const text = (element: Element | null | undefined) =>
    element?.textContent?.replace(/\s+/g, ' ').trim();

  /** Shows the button to a person who has these notifications, of which so many are unread. */
  async function show(items: object[], unread: number): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', component: Page }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NotificationsButton);
    fixture.detectChanges();
    backend.expectOne('/api/notifications').flush({ unread, items });
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => {
    fixture.destroy();
    backend.verify();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('says how many notifications are unread, in its name and in a badge', async () => {
    await show([approved(3, 'Winter update', false), approved(2, 'Autumn update', false)], 2);

    expect(button().getAttribute('aria-label')).toBe('Notifications, 2 unread');
    expect(text(fixture.nativeElement.querySelector('[data-unread]'))).toBe('2');
  });

  it('has no badge and a plain name when everything is read', async () => {
    await show([approved(3, 'Winter update', true)], 0);

    expect(button().getAttribute('aria-label')).toBe('Notifications');
    expect(fixture.nativeElement.querySelector('[data-unread]')).toBeNull();
  });

  it('lists what happened in words, leading to the Approved view, and marks what it shows as read', async () => {
    await show([approved(3, 'Winter update', false), approved(2, 'Autumn update', true)], 1);

    button().click();
    const marked = backend.expectOne({ method: 'POST', url: '/api/notifications/read' });
    expect(marked.request.body).toEqual({ upTo: 3 });
    marked.flush({ unread: 0 });
    await vi.waitFor(() => expect(list()).not.toBeNull());

    expect(Array.from(list()!.querySelectorAll('li')).map((entry) => text(entry))).toEqual([
      expect.stringMatching(
        /^Unread: Mia Manager approved Winter update as Approved v3 of Compact SUV 2026 .*2026/,
      ),
      expect.stringMatching(
        /^Mia Manager approved Autumn update as Approved v2 of Compact SUV 2026 .*2026/,
      ),
    ]);
    expect(list()!.querySelector('a')!.getAttribute('href')).toBe('/approved/3');
    await vi.waitFor(() => expect(button().getAttribute('aria-label')).toBe('Notifications'));
  });

  it('says so when there is nothing to list, and marks nothing', async () => {
    await show([], 0);

    button().click();

    await vi.waitFor(() =>
      expect(text(list()?.querySelector('[data-no-notifications]'))).toBe(
        'You have no notifications.',
      ),
    );
  });

  it('looks again on every navigation', async () => {
    await show([], 0);

    await TestBed.inject(Router).navigateByUrl('/elsewhere');

    backend.expectOne('/api/notifications').flush({
      unread: 1,
      items: [approved(4, 'Spring update', false)],
    });
    await vi.waitFor(() =>
      expect(button().getAttribute('aria-label')).toBe('Notifications, 1 unread'),
    );
  });

  it('looks again every minute while the page is visible, and not while it is hidden', async () => {
    vi.useFakeTimers();
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    await show([], 0);

    vi.advanceTimersByTime(60_000);
    backend.expectNone('/api/notifications');

    visibility.mockReturnValue('visible');
    vi.advanceTimersByTime(60_000);
    backend.expectOne('/api/notifications').flush({ unread: 0, items: [] });
  });

  it('keeps what it showed when a look fails', async () => {
    await show([approved(3, 'Winter update', false)], 1);

    await TestBed.inject(Router).navigateByUrl('/elsewhere');
    backend.expectOne('/api/notifications').flush(null, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();

    expect(button().getAttribute('aria-label')).toBe('Notifications, 1 unread');
  });
});
