import { HttpErrorResponse } from '@angular/common/http';
import { Injector } from '@angular/core';
import { MessageService, ToastMessageOptions } from 'primeng/api';
import { Catalog, CatalogEdit, Catalogs, Edited } from '../../core/catalogs';
import { Issue } from '../../shared/availability-matrix/matrix';
import { Editing, Outcome } from './editing';

const noTrims: Issue = {
  code: 'NO_TRIMS',
  severity: 'ERROR',
  trimId: null,
  regionCode: null,
  featureId: null,
  relatedFeatureIds: [],
  rule: null,
  message: 'The catalog has no trims.',
};

/** Catalog 41, a working copy of the signed-in person's in status Draft, read at revision 4. */
const workingCopy = {
  name: 'Winter update',
  owned: true,
  stale: false,
  issues: [noTrims],
  snapshot: { catalogId: 41, status: 'DRAFT', revision: 4 },
} as unknown as Catalog;

/** The working copy as read again, at another revision or status. */
const readAt = (revision: number, status = 'DRAFT', more: object = {}) =>
  ({
    ...workingCopy,
    ...more,
    snapshot: { ...workingCopy.snapshot, revision, status },
  }) as unknown as Catalog;

describe('Editing', () => {
  /** The reads of the catalog asked for so far, each waiting to be answered by the test. */
  let reads: {
    catalogId: number;
    answer: (catalog: Catalog) => void;
    fail: (error: unknown) => void;
  }[];

  /** The edits sent so far, each waiting to be answered by the test. */
  let sent: {
    edit: string;
    revision: number;
    answer: (answer: Edited) => void;
    fail: (error: unknown) => void;
  }[];

  /** What the person has been told, in order. */
  let told: ToastMessageOptions[];

  let editing: Editing;

  /** Lets Editing react to an answer. */
  const settled = () => new Promise((resolve) => setTimeout(resolve));

  /** An edit that sends itself as the test sees it, and waits for the test to answer it. */
  const edit =
    (name: string): CatalogEdit =>
    (revision) =>
      new Promise((answer, fail) => sent.push({ edit: name, revision, answer, fail }));

  const whatWasSent = () => sent.map(({ edit, revision }) => [edit, revision]);

  /** What the backend answers when it turns an edit down, with the reason it gives. */
  const refusal = (status: number, code?: string, detail = 'Said why.') =>
    new HttpErrorResponse({ status, error: code === undefined ? { detail } : { code, detail } });

  beforeEach(() => {
    sent = [];
    reads = [];
    told = [];
    // Editing is made by an injector of its own, with fakes of what it reads, sends, and tells.
    editing = Injector.create({
      providers: [
        Editing,
        {
          provide: Catalogs,
          useValue: {
            find: (catalogId: number) =>
              new Promise((answer, fail) => reads.push({ catalogId, answer, fail })),
            rename: (catalogId: number, revision: number, name: string) =>
              edit(`rename ${catalogId} to ${name}`)(revision),
          },
        },
        {
          provide: MessageService,
          useValue: { add: (message: ToastMessageOptions) => told.push(message) },
        },
      ],
    }).get(Editing);
  });

  /** Opens catalog 41 and answers the read with the catalog given. */
  async function open(catalog: Catalog = workingCopy): Promise<void> {
    const opened = editing.open(41);
    reads[0].answer(catalog);
    await opened;
  }

  it('opens the working copy as it is read, with its issues and whether it is stale', async () => {
    const opened = editing.open(41);
    expect(editing.catalog()).toBeNull();
    expect(reads.map(({ catalogId }) => catalogId)).toEqual([41]);

    reads[0].answer({ ...workingCopy, stale: true });

    await expect(opened).resolves.toBe(true);
    expect(editing.catalog()?.name).toBe('Winter update');
    expect(editing.issues()).toEqual([noTrims]);
    expect(editing.stale()).toBe(true);
    expect(editing.editable()).toBe(true);
    expect(editing.stopped()).toBeNull();
  });

  it('sends an edit made from the revision read, one at a time, each from where the one before led', async () => {
    await open();

    void editing.edit(edit('first'));
    void editing.edit(edit('second'));
    void editing.edit(edit('third'));
    await settled();
    expect(whatWasSent()).toEqual([['first', 4]]);

    sent[0].answer({ revision: 5, issues: [] });
    await settled();
    expect(whatWasSent()).toEqual([
      ['first', 4],
      ['second', 5],
    ]);

    sent[1].answer({ revision: 6, issues: [] });
    await settled();
    expect(whatWasSent()).toEqual([
      ['first', 4],
      ['second', 5],
      ['third', 6],
    ]);
  });

  it('answers saved, or unchanged when the revision stays, which it tells the person', async () => {
    await open();
    const first = editing.edit(edit('first'));
    const second = editing.edit(edit('second'));
    await settled();

    sent[0].answer({ revision: 5, issues: [] });
    await settled();
    expect(told).toEqual([]);
    sent[1].answer({ revision: 5, issues: [] });

    await expect(first).resolves.toEqual({ kind: 'saved' });
    await expect(second).resolves.toEqual({ kind: 'unchanged' });
    expect(told).toEqual([
      {
        severity: 'info',
        summary: 'No new changes',
        detail: 'The catalog was already as you set it, so nothing was saved.',
      },
    ]);
  });

  it('is idle only while no edit is waiting for its outcome, and says when that is', async () => {
    await open();
    expect(editing.idle()).toBe(true);

    void editing.edit(edit('first'));
    void editing.edit(edit('second'));
    let idle = false;
    void editing.whenIdle().then(() => (idle = true));
    expect(editing.idle()).toBe(false);
    await settled();
    sent[0].answer({ revision: 5, issues: [] });
    await settled();
    expect(editing.idle()).toBe(false);
    expect(idle).toBe(false);
    sent[1].answer({ revision: 6, issues: [] });
    await settled();

    expect(editing.idle()).toBe(true);
    expect(idle).toBe(true);
  });

  describe('folding an answer in', () => {
    it('takes the issues and staleness each edit answers with', async () => {
      await open();
      expect(editing.issues()).toEqual([noTrims]);

      void editing.edit(edit('add a trim'));
      await settled();
      sent[0].answer({ revision: 5, issues: [], stale: true });
      await settled();

      expect(editing.issues()).toEqual([]);
      expect(editing.stale()).toBe(true);
    });

    it('does not let an answer of a lower revision than one already heard change anything', async () => {
      await open();
      void editing.edit(edit('first'));
      void editing.edit(edit('second'));
      void editing.edit(edit('third'));
      await settled();
      sent[0].answer({ revision: 6, issues: [], stale: true });
      await settled();

      sent[1].answer({ revision: 5, issues: [noTrims], stale: false });
      await settled();

      expect(editing.issues()).toEqual([]);
      expect(editing.stale()).toBe(true);
      expect(whatWasSent()).toEqual([
        ['first', 4],
        ['second', 6],
        ['third', 6],
      ]);
    });

    it('takes the catalog to have no issues, and not to be stale, when it is read without them', async () => {
      // A backend that is one release behind reads a catalog without them.
      await open({ ...workingCopy, issues: undefined, stale: undefined } as unknown as Catalog);

      expect(editing.issues()).toEqual([]);
      expect(editing.stale()).toBe(false);
    });

    it('takes the catalog to have no issues, and not to be stale, when an answer names neither', async () => {
      await open({ ...workingCopy, stale: true });
      void editing.edit(edit('first'));
      await settled();

      // A backend that is one release behind answers with the revision alone.
      sent[0].answer({ revision: 5 });
      await settled();

      expect(editing.issues()).toEqual([]);
      expect(editing.stale()).toBe(false);
    });
  });

  describe('when an edit fails', () => {
    it.each([
      [412, undefined, 'stopped', 'conflict'],
      [404, undefined, 'stopped', 'closed'],
      [409, 'NOT_DRAFT', 'stopped', 'closed'],
      [409, 'NAME_TAKEN', 'refused', undefined],
      [409, undefined, 'refused', undefined],
      [422, 'VALIDATION', 'refused', undefined],
      [400, undefined, 'refused', undefined],
      [403, undefined, 'refused', undefined],
      [428, undefined, 'refused', undefined],
      [0, undefined, 'stopped', 'uncertain'],
      [500, undefined, 'stopped', 'uncertain'],
      [503, undefined, 'stopped', 'uncertain'],
      // An answer that says the edit was saved, but cannot be read.
      [200, undefined, 'stopped', 'uncertain'],
    ])('takes an answer of status %i (%s) as %s %s', async (status, code, kind, why) => {
      await open();
      const outcome = editing.edit(edit('first'));
      await settled();

      sent[0].fail(refusal(status, code));

      expect(await outcome).toMatchObject(why ? { kind, why } : { kind });
      expect(editing.stopped()).toBe(why ?? null);
    });

    it('takes no answer at all, such as a save given up on, as uncertain', async () => {
      await open();
      const outcome = editing.edit(edit('first'));
      await settled();

      sent[0].fail(new Error('No answer came in time.'));

      expect(await outcome).toMatchObject({ kind: 'stopped', why: 'uncertain' });
      expect(editing.stopped()).toBe('uncertain');
    });

    it('drops an edit the backend turns down, with its reason, and goes on from the same revision', async () => {
      await open();
      const first = editing.edit(edit('first'));
      const second = editing.edit(edit('second'));
      await settled();

      sent[0].fail(refusal(422, 'VALIDATION', 'This cell cannot be set.'));
      await expect(first).resolves.toEqual({ kind: 'refused', reason: 'This cell cannot be set.' });
      await settled();
      expect(whatWasSent()).toEqual([
        ['first', 4],
        ['second', 4],
      ]);
      sent[1].answer({ revision: 5, issues: [] });

      await expect(second).resolves.toEqual({ kind: 'saved' });
      expect(editing.editable()).toBe(true);
    });
  });

  it('stops after a revision conflict, sending neither the edits queued nor new ones until it is read again', async () => {
    await open();
    const first = editing.edit(edit('first'));
    const second = editing.edit(edit('second'));
    await settled();

    sent[0].fail(refusal(412, 'REVISION_CONFLICT', 'Changed somewhere else.'));

    await expect(first).resolves.toEqual({
      kind: 'stopped',
      why: 'conflict',
      reason: 'Changed somewhere else.',
    });
    await expect(second).resolves.toEqual({ kind: 'stopped', why: 'not sent' });
    await expect(editing.edit(edit('third'))).resolves.toEqual({
      kind: 'stopped',
      why: 'not sent',
    });
    expect(editing.editable()).toBe(false);
    expect(whatWasSent()).toEqual([['first', 4]]);

    // Reading the catalog again lets editing go on, from the revision read.
    const reloaded = editing.reload();
    reads[1].answer(readAt(9));
    await expect(reloaded).resolves.toBe(true);
    expect(editing.stopped()).toBeNull();
    expect(editing.editable()).toBe(true);
    void editing.edit(edit('fourth'));
    await settled();
    expect(whatWasSent()).toEqual([
      ['first', 4],
      ['fourth', 9],
    ]);
  });

  describe('when the catalog is no longer in status Draft', () => {
    const NOT_DRAFT = 'Only a catalog in status Draft can be edited.';

    /**
     * Sends two edits, of which the backend refuses the first because the catalog is closed, and
     * gives the outcome of the second.
     */
    async function editClosed(): Promise<{ second: Promise<Outcome> }> {
      await open();
      const first = editing.edit(edit('first'));
      const second = editing.edit(edit('second'));
      await settled();
      sent[0].fail(refusal(409, 'NOT_DRAFT', NOT_DRAFT));
      await expect(first).resolves.toEqual({ kind: 'stopped', why: 'closed', reason: NOT_DRAFT });

      return { second };
    }

    it('reads it again at once, read-only, and says why', async () => {
      const { second } = await editClosed();

      expect(told).toEqual([{ severity: 'warn', summary: 'Not saved', detail: NOT_DRAFT }]);
      expect(reads).toHaveLength(2);
      reads[1].answer(readAt(5, 'SUBMITTED'));
      await settled();

      await expect(second).resolves.toEqual({ kind: 'stopped', why: 'not sent' });
      expect(whatWasSent()).toEqual([['first', 4]]);
      expect(editing.catalog()?.snapshot.status).toBe('SUBMITTED');
      expect(editing.stopped()).toBeNull();
      expect(editing.editable()).toBe(false);
    });

    it('stays stopped when it could not be read again', async () => {
      await editClosed();

      reads[1].fail(refusal(503));
      await settled();

      expect(editing.stopped()).toBe('closed');
      expect(editing.catalog()?.snapshot.status).toBe('DRAFT');
      expect(editing.failed()).toBe(false);
    });

    it('is missing when it is no longer there', async () => {
      await editClosed();

      reads[1].fail(refusal(404, 'NOT_FOUND'));
      await settled();

      expect(editing.catalog()).toBeNull();
      expect(editing.missing()).toBe(true);
    });
  });

  it('says when the catalog could not be read, or the address names none the person may open', async () => {
    const opened = editing.open(41);
    reads[0].fail(refusal(500));
    await expect(opened).resolves.toBe(false);
    expect(editing.failed()).toBe(true);
    expect(editing.missing()).toBe(false);

    const reloaded = editing.reload();
    expect(editing.failed()).toBe(false);
    reads[1].fail(refusal(400));
    await expect(reloaded).resolves.toBe(false);
    expect(editing.missing()).toBe(true);
    expect(editing.failed()).toBe(false);
  });

  describe('a change of what the page is made of', () => {
    it('reads the catalog again once it is saved, and queues no edit while that read is under way', async () => {
      await open();
      const changed = editing.restructure(edit('add a trim'));
      await settled();
      sent[0].answer({ revision: 5, issues: [] });
      await settled();
      expect(reads).toHaveLength(2);

      const meanwhile = editing.edit(edit('set a cell'));
      await settled();
      reads[1].answer(readAt(5, 'DRAFT', { name: 'With its new trim' }));

      await expect(changed).resolves.toEqual({ kind: 'saved' });
      await expect(meanwhile).resolves.toEqual({ kind: 'stopped', why: 'not sent' });
      expect(editing.catalog()?.name).toBe('With its new trim');
      expect(editing.editable()).toBe(true);
      void editing.edit(edit('set a cell'));
      await settled();
      expect(whatWasSent()).toEqual([
        ['add a trim', 4],
        ['set a cell', 5],
      ]);
    });

    it('queues no edit behind it while it is on its way', async () => {
      await open();
      void editing.restructure(edit('add a trim'));

      await expect(editing.edit(edit('set a cell'))).resolves.toEqual({
        kind: 'stopped',
        why: 'not sent',
      });
      await settled();
      expect(whatWasSent()).toEqual([['add a trim', 4]]);
    });

    it('reads nothing again when the change is refused', async () => {
      await open();
      const changed = editing.restructure(edit('add a trim'));
      await settled();

      sent[0].fail(refusal(422, 'VALIDATION', 'No such trim.'));

      await expect(changed).resolves.toEqual({ kind: 'refused', reason: 'No such trim.' });
      expect(reads).toHaveLength(1);
      void editing.edit(edit('set a cell'));
      await settled();
      expect(whatWasSent()).toEqual([
        ['add a trim', 4],
        ['set a cell', 4],
      ]);
    });

    it('stops editing as behind what is saved when the catalog cannot be read again', async () => {
      await open();
      const changed = editing.restructure(edit('add a trim'));
      await settled();
      sent[0].answer({ revision: 5, issues: [] });
      await settled();

      reads[1].fail(refusal(503));

      await expect(changed).resolves.toEqual({ kind: 'saved' });
      expect(editing.stopped()).toBe('behind');
      expect(editing.editable()).toBe(false);
      const reloaded = editing.reload();
      reads[2].answer(readAt(5));
      await reloaded;
      expect(editing.stopped()).toBeNull();
    });
  });

  describe('reading the catalog again as saved', () => {
    it('waits for the edits on their way, takes none meanwhile, and goes on from the revision read', async () => {
      await open();
      void editing.edit(edit('set a cell'));
      const read = editing.readAgain();
      expect(editing.reading()).toBe(true);
      expect(editing.editable()).toBe(false);
      await settled();
      expect(reads).toHaveLength(1);

      sent[0].answer({ revision: 5, issues: [] });
      await settled();
      expect(reads).toHaveLength(2);
      await expect(editing.edit(edit('meanwhile'))).resolves.toEqual({
        kind: 'stopped',
        why: 'not sent',
      });
      reads[1].answer(readAt(6));

      await expect(read).resolves.toBe(true);
      expect(editing.reading()).toBe(false);
      expect(editing.editable()).toBe(true);
      void editing.edit(edit('set another cell'));
      await settled();
      expect(whatWasSent()).toEqual([
        ['set a cell', 4],
        ['set another cell', 6],
      ]);
    });

    it('leaves editing as it was when the catalog cannot be read', async () => {
      await open();
      const read = editing.readAgain();
      await settled();

      reads[1].fail(refusal(503));

      await expect(read).resolves.toBe(false);
      expect(editing.editable()).toBe(true);
      expect(editing.stopped()).toBeNull();
    });
  });

  describe('an update from Approved', () => {
    it('is sent when it names the revision editing is at', async () => {
      await open();

      void editing.restructure(edit('update'), 4);
      await settled();

      expect(whatWasSent()).toEqual([['update', 4]]);
    });

    it('stops editing as a revision conflict, without sending, when it names another revision', async () => {
      await open();
      void editing.edit(edit('set a cell'));
      const updated = editing.restructure(edit('update'), 4);
      await settled();
      sent[0].answer({ revision: 5, issues: [] });

      expect(await updated).toMatchObject({ kind: 'stopped', why: 'conflict' });
      expect(editing.stopped()).toBe('conflict');
      expect(whatWasSent()).toEqual([['set a cell', 4]]);
      expect(reads).toHaveLength(1);
    });
  });

  it.each([
    ['someone else owns', { ...workingCopy, owned: false }],
    ['is Submitted', readAt(4, 'SUBMITTED')],
    ['is Approved', readAt(4, 'APPROVED')],
  ])('sends no edit of a catalog that %s', async (_, catalog) => {
    await open(catalog);

    expect(editing.editable()).toBe(false);
    await expect(editing.edit(edit('set a cell'))).resolves.toEqual({
      kind: 'stopped',
      why: 'not sent',
    });
    expect(sent).toEqual([]);
  });

  it('renames the catalog, and shows the new name once it is saved', async () => {
    await open();

    const renamed = editing.rename('Spring update');
    await settled();
    expect(whatWasSent()).toEqual([['rename 41 to Spring update', 4]]);
    expect(editing.catalog()?.name).toBe('Winter update');
    sent[0].answer({ revision: 5, issues: [] });

    await expect(renamed).resolves.toEqual({ kind: 'saved' });
    expect(editing.catalog()?.name).toBe('Spring update');
  });
});
