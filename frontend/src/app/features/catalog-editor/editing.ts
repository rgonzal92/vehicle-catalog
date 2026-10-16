import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { MessageService } from 'primeng/api';
import { Catalog, CatalogEdit, Catalogs, Edited } from '../../core/catalogs';
import { reasonOf } from '../../shared/reason-of';

/**
 * How a sent edit failed, which decides what happens next.
 *
 * - `rejected`: the backend turned this edit down for what it asks, and nothing else is wrong.
 *   Editing goes on.
 * - `conflict`: a revision conflict. The catalog has to be read again before any further edit.
 * - `closed`: the catalog can no longer be edited at all, because it is no longer in status Draft
 *   or what the edit is about is no longer there.
 * - `uncertain`: no answer says whether the edit was saved.
 */
type SaveFailure = 'rejected' | 'conflict' | 'closed' | 'uncertain';

/** The failures after which no further edit is sent. */
type SaveStop = Exclude<SaveFailure, 'rejected'>;

/**
 * Why editing has stopped until the catalog is read again: an edit failed in a way that stops it,
 * or `behind`, an edit was saved but the catalog could not be read again, so what the page shows
 * is behind what is saved.
 */
export type Stop = SaveStop | 'behind';

/**
 * What became of an edit: it was saved; it asked for what was already so, and changed nothing; the
 * backend refused it, with its reason; or it stopped editing, with the backend's reason. An edit
 * made while editing has stopped, or while it takes no edit, is not sent.
 */
export type Outcome =
  | { kind: 'saved' }
  | { kind: 'unchanged' }
  | { kind: 'refused'; reason: string }
  | { kind: 'stopped'; why: SaveStop; reason: string }
  | { kind: 'stopped'; why: 'not sent' };

const NOT_SENT: Outcome = { kind: 'stopped', why: 'not sent' };

/** What is known of the catalog before any revision of it has been heard of. */
const NOTHING_HEARD: Required<Pick<Edited, 'revision' | 'issues' | 'stale'>> = {
  revision: -1,
  issues: [],
  stale: false,
};

/** Why an edit worked out from another revision than editing is at was not sent. */
const WORKED_OUT_BEFORE = 'The catalog changed after this edit was worked out.';

/** Tells how a sent edit failed from what it was rejected with. */
function failureOf(error: unknown): SaveFailure {
  // Only an answer that turns the edit down says it was not saved. A failed connection, a server
  // error, an answer that cannot be read, and no answer at all leave that open.
  if (!(error instanceof HttpErrorResponse) || error.status < 400 || error.status >= 500) {
    return 'uncertain';
  }
  switch (error.status) {
    case 412:
      return 'conflict';
    case 404:
      return 'closed';
    case 409:
      // A catalog that is not in status Draft takes no edit. Any other conflict, such as a name
      // that is taken, is about this edit alone.
      return (error.error as { code?: unknown } | null)?.code === 'NOT_DRAFT'
        ? 'closed'
        : 'rejected';
    default:
      return 'rejected';
  }
}

/**
 * The working copy the editor has open: the catalog as read, the one revision its next edit is
 * made from, the edits on their way, the issues and staleness of the highest revision heard, and
 * why editing has stopped. The editor page provides it, and its tabs and dialogs edit through it.
 *
 * Edits are sent one at a time, in the order they were made, each from the revision the one before
 * led to. An edit the backend turns down for its own sake is dropped and the next one goes out. Any
 * other failure stops editing: the edits behind it are not sent, no edit is sent again, and none
 * is taken until the catalog is read again.
 */
@Injectable()
export class Editing {
  private readonly catalogs = inject(Catalogs);
  private readonly messages = inject(MessageService);

  /** The catalog being edited, by its id. */
  private id = 0;

  /** The revision the next edit is made from: the one read, or where the last saved edit led. */
  private revision = 0;

  /** How many times the catalog has been read. An edit queued before the last read is not sent. */
  private reads = 0;

  /**
   * Whether an edit of what the page is made of, or the read again after it, is on its way. No
   * edit is queued meanwhile, since the read starts editing over from the revision read.
   */
  private restructuring = false;

  /** Settles when the last edit queued so far has had its outcome. */
  private last: Promise<unknown> = Promise.resolve();

  /** How many edits have been queued and not yet had their outcome. */
  private readonly waiting = signal(0);

  /** Whether every edit queued so far has had its outcome. */
  readonly idle = computed(() => this.waiting() === 0);

  private readonly asRead = signal<Catalog | null>(null);

  /** The working copy as it was last read, or null until it has been. */
  readonly catalog = this.asRead.asReadonly();

  private readonly absent = signal(false);

  /** Whether the address names no catalog, or one this person may not open. */
  readonly missing = this.absent.asReadonly();

  private readonly unread = signal(false);

  /** Whether the catalog could not be read at all, which leaves nothing to show. */
  readonly failed = this.unread.asReadonly();

  /** What the highest revision heard of says of the catalog. */
  private readonly heard = signal(NOTHING_HEARD);

  /** The catalog's issues as of the highest revision heard, Errors before Warnings. */
  readonly issues = computed(() => this.heard().issues);

  /** Whether the catalog is stale as of the highest revision heard. */
  readonly stale = computed(() => this.heard().stale);

  private readonly readingAgain = signal(false);

  /**
   * Whether the catalog is being read again as saved, before something that needs it so. Nothing
   * can be edited meanwhile, so that no change is made from the revision being left behind.
   */
  readonly reading = this.readingAgain.asReadonly();

  private readonly stop = signal<Stop | null>(null);

  /** Why editing has stopped, or null while it goes on. */
  readonly stopped = this.stop.asReadonly();

  /**
   * Whether the person may edit the catalog: it is theirs and in status Draft, editing goes on, and
   * the catalog is not being read again.
   */
  readonly editable = computed(() => {
    const catalog = this.asRead();
    return (
      !!catalog?.owned &&
      catalog.snapshot.status === 'DRAFT' &&
      !this.stop() &&
      !this.readingAgain()
    );
  });

  /** Reads the catalog, to edit it from the revision read. It says whether the catalog was read. */
  open(catalogId: number): Promise<boolean> {
    this.id = catalogId;
    return this.load();
  }

  /** Reads the catalog again, which shows what was saved and lets editing go on. */
  reload(): Promise<boolean> {
    return this.load();
  }

  /**
   * Queues an edit behind the ones on their way, and answers with what became of it. One that asks
   * for what is already so leaves the revision where it was, and the person is told so.
   */
  edit(edit: CatalogEdit): Promise<Outcome> {
    return this.queue(edit, false);
  }

  /**
   * Queues an edit of the catalog's feature rows, trims, regions, offerings, rules, or status
   * behind the edits on their way, and reads the catalog again once it is saved, since such an edit
   * alters what the page is made of. Its outcome comes once the catalog has been read.
   *
   * @param from the revision the edit was worked out from, when it was worked out from the catalog
   *   as saved rather than as read, as an update from Approved is. The edit is sent only when that
   *   is the revision editing is at, and otherwise stops editing as a revision conflict.
   */
  restructure(edit: CatalogEdit, from?: number): Promise<Outcome> {
    return this.queue(edit, true, from);
  }

  private queue(edit: CatalogEdit, readAgain: boolean, from?: number): Promise<Outcome> {
    if (!this.editable() || this.restructuring) {
      return Promise.resolve(NOT_SENT);
    }
    if (readAgain) {
      this.restructuring = true;
    }
    const read = this.reads;
    const outcome = this.last.then(async (): Promise<Outcome> => {
      try {
        // The editor never sends an edit a second time, nor one made from what was read before.
        if (this.stop() || read !== this.reads) {
          return NOT_SENT;
        }
        if (from !== undefined && from !== this.revision) {
          this.stop.set('conflict');
          return { kind: 'stopped', why: 'conflict', reason: WORKED_OUT_BEFORE };
        }
        const sent = await this.send(edit);
        if (readAgain && (sent.kind === 'saved' || sent.kind === 'unchanged')) {
          if (!(await this.load())) {
            this.stop.set('behind');
          }
        }
        return sent;
      } finally {
        if (readAgain) {
          this.restructuring = false;
        }
      }
    });
    this.waiting.update((waiting) => waiting + 1);
    this.last = outcome.then(() => this.waiting.update((waiting) => waiting - 1));
    return outcome;
  }

  /** Renames the working copy behind the edits on their way, and shows the name once it is saved. */
  async rename(name: string): Promise<Outcome> {
    const outcome = await this.edit((revision) => this.catalogs.rename(this.id, revision, name));
    if (outcome.kind === 'saved') {
      this.asRead.update((catalog) => catalog && { ...catalog, name });
    }
    return outcome;
  }

  /**
   * Reads the catalog again as it is saved, once the edits on their way have had their outcome, for
   * what needs it so, such as the dialog that says how many cells go with a removal. It says
   * whether the catalog was read; when it was not, editing goes on as it was.
   */
  async readAgain(): Promise<boolean> {
    this.readingAgain.set(true);
    try {
      await this.whenIdle();
      return !this.stop() && (await this.load());
    } finally {
      this.readingAgain.set(false);
    }
  }

  /** Settles once every edit queued so far has had its outcome. */
  async whenIdle(): Promise<void> {
    await this.last;
  }

  private async send(edit: CatalogEdit): Promise<Outcome> {
    const from = this.revision;
    let answer: Edited;
    try {
      answer = await edit(from);
    } catch (error) {
      const failure = failureOf(error);
      const reason = reasonOf(error);
      if (failure === 'rejected') {
        return { kind: 'refused', reason };
      }
      this.stop.set(failure);
      if (failure === 'closed') {
        this.messages.add({ severity: 'warn', summary: 'Not saved', detail: reason });
        // It is read again as it is now, read-only. The outcome does not wait for the read, so that
        // the matrix marks the cell the edit was about before the catalog it shows is replaced.
        void this.load();
      }
      return { kind: 'stopped', why: failure, reason };
    }
    // An answer of a lower revision than one already heard changes nothing.
    this.revision = Math.max(this.revision, answer.revision);
    this.hear(answer);
    if (this.revision !== from) {
      return { kind: 'saved' };
    }
    this.messages.add({
      severity: 'info',
      summary: 'No new changes',
      detail: 'The catalog was already as you set it, so nothing was saved.',
    });
    return { kind: 'unchanged' };
  }

  /**
   * Takes the issues and staleness an answer brings, unless an answer of a higher revision has been
   * heard already: one that was slow to arrive says what they were, not what they are.
   */
  private hear({ revision, issues, stale }: Edited): void {
    if (revision >= this.heard().revision) {
      // A backend that is one release behind answers without them.
      this.heard.set({ revision, issues: issues ?? [], stale: !!stale });
    }
  }

  /**
   * Reads the catalog, and starts editing over from the revision read. It says whether the catalog
   * was read. A catalog that could not be read again stays in view as it was.
   */
  private async load(): Promise<boolean> {
    this.unread.set(false);
    let catalog: Catalog;
    try {
      catalog = await this.catalogs.find(this.id);
    } catch (error) {
      // The backend refuses an address that names no catalog the person may open. Any other
      // failure has been shown as a message.
      if (error instanceof HttpErrorResponse && [400, 404].includes(error.status)) {
        this.asRead.set(null);
        this.absent.set(true);
      }
      this.unread.set(!this.absent() && !this.asRead());
      return false;
    }
    this.reads++;
    this.asRead.set(catalog);
    this.revision = catalog.snapshot.revision;
    this.stop.set(null);
    // A read starts editing over, so what it brings stands, whatever was heard before.
    this.heard.set(NOTHING_HEARD);
    this.hear({
      revision: catalog.snapshot.revision,
      issues: catalog.issues,
      stale: catalog.stale,
    });
    return true;
  }
}
