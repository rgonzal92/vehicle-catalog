import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { LibraryList, REGIONS, TRIMS } from './library-list';
import { LibraryListPage } from './library-list-page';

describe('LibraryListPage', () => {
  let backend: HttpTestingController;

  const trims = [
    { id: 1, name: 'Base', sortOrder: 1, active: true },
    { id: 2, name: 'Sport', sortOrder: 2, active: false },
  ];
  const regions = [{ code: 'EU', name: 'Europe', sortOrder: 1, active: true }];

  const rows = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');

  const button = (element: HTMLElement, label: string) =>
    element.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);

  /** Renders the page for one of the library's lists, holding these entries. */
  async function page(list: LibraryList, entries: object[]): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        MessageService,
        { provide: ActivatedRoute, useValue: { snapshot: { data: { list } } } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(LibraryListPage);
    fixture.detectChanges();
    backend.expectOne(list.path).flush(entries);
    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() =>
      expect(rows(element)[0]).toContain((entries[0] as { name: string }).name),
    );

    return element;
  }

  it('lists trims in the order the backend gives, with whether each is active', async () => {
    const element = await page(TRIMS, trims);

    expect(rows(element)[0]).toContain('Base');
    expect(rows(element)[0]).toContain('Active');
    expect(rows(element)[1]).toContain('Sport');
    expect(rows(element)[1]).toContain('Inactive');
  });

  it('shows each region with its code', async () => {
    const element = await page(REGIONS, regions);

    expect(rows(element)[0]).toContain('EU');
    expect(rows(element)[0]).toContain('Europe');
  });

  it('moves a trim up one place and reads the list again', async () => {
    const element = await page(TRIMS, trims);

    button(element, 'Move Sport up')?.click();
    const request = backend.expectOne({ method: 'PUT', url: '/api/trims/2' });
    expect(request.request.body).toEqual({ name: 'Sport', sortOrder: 1, active: false });
    request.flush({ ...trims[1], sortOrder: 1 });
    const reread = await vi.waitFor(() => backend.expectOne({ method: 'GET', url: '/api/trims' }));
    reread.flush([
      { ...trims[1], sortOrder: 1 },
      { ...trims[0], sortOrder: 2 },
    ]);

    await vi.waitFor(() => expect(rows(element)[0]).toContain('Sport'));
  });

  it('cannot move the first entry up or the last one down', async () => {
    const element = await page(TRIMS, trims);

    expect(button(element, 'Move Base up')?.disabled).toBe(true);
    expect(button(element, 'Move Base down')?.disabled).toBe(false);
    expect(button(element, 'Move Sport down')?.disabled).toBe(true);
  });

  it('addresses a region by its code when it changes one', async () => {
    const element = await page(REGIONS, regions);

    button(element, 'Deactivate Europe')?.click();

    const request = backend.expectOne({ method: 'PUT', url: '/api/regions/EU' });
    expect(request.request.body).toEqual({ name: 'Europe', sortOrder: 1, active: false });
  });
});
