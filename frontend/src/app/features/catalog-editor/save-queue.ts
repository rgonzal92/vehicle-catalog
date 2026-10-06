import { HttpErrorResponse } from '@angular/common/http';
import { computed, signal } from '@angular/core';

/**
 * How a save failed, which decides what the editor does next.
 *
 * - `rejected`: the backend turned this edit down for what it asks, and nothing else is wrong.
 *   Editing goes on.
 * - `conflict`: a revision conflict. The catalog has to be read again before any further edit.
 * - `closed`: the catalog can no longer be edited at all, because it is no longer in status Draft
 *   or what the edit is about is no longer there.
 * - `uncertain`: no answer says whether the edit was saved.
 */
export type SaveFailure = 'rejected' | 'conflict' | 'closed' | 'uncertain';

/** The failures after which no further edit is sent. */
export type SaveStop = Exclude<SaveFailure, 'rejected'>;

/** Tells how a save failed from what it was rejected with. */
export function failureOf(error: unknown): SaveFailure {
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

/** What an edit is rejected with when it was never sent, because the queue had stopped. */
export class NotSent extends Error {}

/**
 * Sends a catalog's edits one at a time, in the order they were made. Each one names the revision
 * the save before it led to, so the backend can refuse a write that has not seen the latest change.
 *
 * An edit the backend turns down for its own sake is dropped and the next one goes out. Any other
 * failure stops the queue for good: the edits behind the failed one are not sent, no edit is sent
 * again, and no later one is accepted. Going on takes a new queue, made from the catalog as it is
 * read again.
 */
export class SaveQueue<Edit> {
  /** Settles when the last edit queued so far has been answered, whatever the answer. */
  private last: Promise<void> = Promise.resolve();

  /** Why no further edit is sent, or null while edits are. */
  private readonly stop = signal<SaveStop | null>(null);

  /** Why the queue has stopped, or null while it is sending. */
  readonly stopped = this.stop.asReadonly();

  /** How many edits have been queued and not yet answered or dropped. */
  private readonly waiting = signal(0);

  /** Whether every edit queued so far has had its outcome. */
  readonly idle = computed(() => this.waiting() === 0);

  /**
   * @param send saves one edit made from a revision, and answers with the revision it led to
   * @param revision the revision the catalog was read at
   */
  constructor(
    private readonly send: (edit: Edit, revision: number) => Promise<number>,
    private revision: number,
  ) {}

  /** Settles once every edit queued so far has had its outcome, whatever it was. */
  whenIdle(): Promise<void> {
    return this.last;
  }

  /**
   * Queues an edit behind the ones before it. The promise settles when its own save does, and is
   * rejected with {@link NotSent} when the queue stopped before its turn. It says whether the edit
   * changed the catalog: one that asks for what is already so leaves the revision where it was.
   */
  add(edit: Edit): Promise<boolean> {
    const saved = this.last.then(async () => {
      if (this.stop()) {
        throw new NotSent();
      }
      try {
        const from = this.revision;
        this.revision = await this.send(edit, from);
        return this.revision !== from;
      } catch (error) {
        const failure = failureOf(error);
        if (failure !== 'rejected') {
          this.stop.set(failure);
        }
        throw error;
      }
    });
    this.waiting.update((waiting) => waiting + 1);
    this.last = saved
      .catch(() => undefined)
      .then(() => this.waiting.update((waiting) => waiting - 1));

    return saved;
  }
}
