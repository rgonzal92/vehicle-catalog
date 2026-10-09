import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { CatalogChanges, CatalogEdit, UpdatePreview } from '../../core/catalogs';
import { UpdateDialog } from './update-dialog';

const nothing: CatalogChanges = {
  trimsAdded: [],
  trimsRemoved: [],
  regionsAdded: [],
  regionsRemoved: [],
  offeringsAdded: [],
  offeringsRemoved: [],
  featureRowsAdded: [],
  featureRowsRemoved: [],
  cellsChanged: [],
  rulesAdded: [],
  rulesRemoved: [],
  rulesChanged: [],
};

const preview: UpdatePreview = {
  revision: 7,
  approved: { catalogId: 13, versionNumber: 4 },
  taken: { ...nothing, regionsAdded: [{ code: 'ASIA', name: 'Asia' }] },
  conflicts: [
    {
      id: 'cell:8:2:NA',
      kind: 'CELL',
      what: 'Tow Package, Sport in North America',
      base: 'Available',
      mine: 'Standard',
      theirs: 'Not offered',
    },
    {
      id: 'region:EU',
      kind: 'REGION',
      what: 'Europe',
      base: 'In the catalog',
      mine: 'Kept, with 2 cells changed beneath it',
      theirs: 'Removed',
    },
  ],
};

@Component({
  imports: [UpdateDialog],
  template: `<app-update-dialog [catalogId]="41" [run]="run" />`,
})
class Host {
  readonly dialog = viewChild.required(UpdateDialog);

  /** How many updates the dialog asked to be sent. Each is sent as it is. */
  sent = 0;

  readonly run = async (edit: CatalogEdit): Promise<void> => {
    this.sent++;
    await edit(7);
  };
}

describe('UpdateDialog', () => {
  let backend: HttpTestingController;
  let messages: MessageService;
  let host: Host;

  const dialog = () => document.querySelector<HTMLElement>('.p-dialog');

  const text = (element: Element | null | undefined) =>
    element?.textContent?.replace(/\s+/g, ' ').trim();

  const button = (label: string) =>
    Array.from(dialog()!.querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    )!;

  /** The radio button that takes a side of a conflict. */
  const side = (conflictId: string, label: string) =>
    Array.from(
      dialog()!.querySelectorAll<HTMLLabelElement>(`[data-conflict="${conflictId}"] label`),
    )
      .find((candidate) => candidate.textContent?.trim() === label)!
      .querySelector('input')!;

  /** Asks for the dialog, and answers the update it works out with this. */
  async function open(answer: UpdatePreview | number): Promise<void> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService],
    });
    backend = TestBed.inject(HttpTestingController);
    messages = TestBed.inject(MessageService);
    vi.spyOn(messages, 'add');
    const fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();

    const opened = fixture.componentInstance.dialog().open();
    const request = backend.expectOne({ method: 'POST', url: '/api/catalogs/41/merge-preview' });
    if (typeof answer === 'number') {
      request.flush(
        { code: 'NOT_STALE', detail: 'This catalog is up to date.' },
        { status: answer, statusText: 'Refused' },
      );
    } else {
      request.flush(answer);
    }
    await opened;
    fixture.detectChanges();
  }

  afterEach(() => backend.verify());

  it('shows what the update takes from Approved, and each conflict with its three values', async () => {
    await open(preview);

    await vi.waitFor(() => expect(dialog()).not.toBeNull());
    expect(text(dialog()!.querySelector('[data-update-summary]'))).toBe(
      'The update brings what Approved v4 changed into this catalog, and keeps what you changed' +
        ' yourself.',
    );
    expect(
      Array.from(dialog()!.querySelectorAll('[aria-labelledby="update-taken"] tbody td')).map(text),
    ).toEqual(['Added', 'Region', 'Asia']);
    const conflicts = dialog()!.querySelector('[aria-labelledby="update-conflicts"]')!;
    expect(Array.from(conflicts.querySelectorAll('th')).map(text)).toEqual([
      'Of',
      'What',
      'Before',
      'In this catalog',
      'In Approved v4',
      'Take',
    ]);
    expect(
      Array.from(conflicts.querySelectorAll('tbody tr')).map((row) =>
        Array.from(row.querySelectorAll('td')).map(text),
      ),
    ).toEqual([
      [
        'Cell',
        'Tow Package, Sport in North America',
        'Available',
        'Standard',
        'Not offered',
        'Mine Approved v4',
      ],
      [
        'Region',
        'Europe',
        'In the catalog',
        'Kept, with 2 cells changed beneath it',
        'Removed',
        'Mine Approved v4',
      ],
    ]);
  });

  it('updates the catalog once a side of every conflict is taken, as it was worked out', async () => {
    await open(preview);
    await vi.waitFor(() => expect(dialog()).not.toBeNull());
    expect(button('Update catalog').disabled).toBe(true);
    expect(text(dialog())).toContain('Take one side of every conflict first.');

    side('cell:8:2:NA', 'Approved v4').click();
    await vi.waitFor(() => expect(side('cell:8:2:NA', 'Approved v4').checked).toBe(true));
    expect(button('Update catalog').disabled, 'with one conflict still open').toBe(true);
    side('region:EU', 'Mine').click();
    await vi.waitFor(() => expect(button('Update catalog').disabled).toBe(false));
    button('Update catalog').click();

    const update = await vi.waitFor(() =>
      backend.expectOne({ method: 'POST', url: '/api/catalogs/41/merge' }),
    );
    expect(update.request.headers.get('If-Match')).toBe('"7"');
    expect(update.request.body).toEqual({
      approvedCatalogId: 13,
      resolutions: { 'cell:8:2:NA': 'THEIRS', 'region:EU': 'MINE' },
    });
    update.flush({ revision: 8, issues: [], stale: false });
    await vi.waitFor(() =>
      expect(messages.add).toHaveBeenCalledWith({
        severity: 'success',
        summary: 'Catalog updated',
        detail: 'It now has Approved v4 as its base.',
      }),
    );
    expect(host.sent).toBe(1);
  });

  it('says why an update was refused, and works it out again when asked', async () => {
    await open({ ...preview, conflicts: [] });
    await vi.waitFor(() => expect(button('Update catalog').disabled).toBe(false));

    button('Update catalog').click();
    (
      await vi.waitFor(() => backend.expectOne({ method: 'POST', url: '/api/catalogs/41/merge' }))
    ).flush(
      { code: 'APPROVED_MOVED', detail: 'Approved v5 is the current version by now.' },
      { status: 409, statusText: 'Conflict' },
    );

    await vi.waitFor(() =>
      expect(text(dialog())).toContain('Approved v5 is the current version by now.'),
    );
    button('Work the update out again').click();
    backend
      .expectOne({ method: 'POST', url: '/api/catalogs/41/merge-preview' })
      .flush({ ...preview, approved: { catalogId: 14, versionNumber: 5 }, conflicts: [] });
    await vi.waitFor(() => expect(text(dialog())).toContain('Taken from Approved v5'));
    expect(text(dialog())).not.toContain('Approved v5 is the current version by now.');
  });

  it('says so when the update has no conflicts, and when it takes nothing', async () => {
    await open({ ...preview, taken: nothing, conflicts: [] });

    await vi.waitFor(() => expect(dialog()).not.toBeNull());
    expect(text(dialog()!.querySelector('[data-no-conflicts]'))).toBe(
      'There are no conflicts: nothing was changed differently in this catalog and in Approved v4.',
    );
    expect(text(dialog()!.querySelector('[aria-labelledby="update-taken"]'))).toContain(
      'Approved v4 brings nothing that this catalog does not have already.',
    );
  });

  it('stays shut and says why when the backend refuses', async () => {
    await open(409);

    expect(dialog()).toBeNull();
    expect(messages.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'No update from Approved',
      detail: 'This catalog is up to date.',
    });
  });
});
