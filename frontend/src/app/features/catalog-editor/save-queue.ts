import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';

/**
 * How a save failed, which decides what the editor does next.
 *
 * - `refused`: the backend refused this edit and nothing else is wrong. Editing goes on.
 * - `conflict`: a revision conflict. The catalog has to be read again before any further edit.
 * - `closed`: the catalog can no longer be edited at all, because it is no longer in status Draft
 *   or no longer there.
 * - `uncertain`: no answer says whether the edit was saved.
 */
export type SaveFailure = 'refused' | 'conflict' | 'closed' | 'uncertain';

/** The failures after which no further edit is sent. */
export type SaveStop = Exclude<SaveFailure, 'refused'>;

/** Tells how a save failed from what it was rejected with. */
export function failureOf(error: unknown): SaveFailure {
  if (!(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500) {
    return 'uncertain';
  }
  switch (error.status) {
    case 412:
      return 'conflict';
    case 404:
    case 409:
      return 'closed';
    default:
      return 'refused';
  }
}

/** What an edit is rejected with when it was never sent, because the queue had stopped. */
export class NotSent extends Error {
  constructor() {
    super('An earlier change was not saved, so this one was not sent.');
  }
}

/** What a save is rejected with when no answer came in time. */
class Unanswered extends Error {}

/**
 * Sends a catalog's edits one at a time, in the order they were made. Each one names the revision
 * the save before it led to, so the backend can refuse a write that has not seen the latest change.
 *
 * An edit the backend refuses for its own sake is dropped and the next one goes out. Any other
 * failure stops the queue for good: the edits behind the failed one are not sent, no edit is sent
 * again, and no later one is accepted. Going on takes a new queue, made from the catalog as it is
 * read again.
 */
export class SaveQueue<Edit> {
  /** Settles when the last edit queued so far has been answered, whatever the answer. */
  private last: Promise<void> = Promise.resolve();

  private readonly stop = signal<SaveStop | null>(null);

  /** Why the queue has stopped, or null while it is sending. */
  readonly stopped = this.stop.asReadonly();

  /**
   * @param send saves one edit made from a revision, and answers with the revision it led to
   * @param revision the revision the catalog was read at
   * @param patience how long, in milliseconds, a save may go unanswered before its outcome counts
   *     as unknown
   */
  constructor(
    private readonly send: (edit: Edit, revision: number) => Promise<number>,
    private revision: number,
    private readonly patience = 20_000,
  ) {}

  /**
   * Queues an edit behind the ones before it. The promise settles when its own save does, and is
   * rejected with {@link NotSent} when the queue stopped before its turn.
   */
  add(edit: Edit): Promise<void> {
    const saved = this.last.then(async () => {
      if (this.stop()) {
        throw new NotSent();
      }
      try {
        this.revision = await this.answered(this.send(edit, this.revision));
      } catch (error) {
        const failure = failureOf(error);
        if (failure !== 'refused') {
          this.stop.set(failure);
        }
        throw error;
      }
    });
    this.last = saved.catch(() => undefined);

    return saved;
  }

  /** The save's answer, or a rejection once it has gone unanswered for too long. */
  private answered(save: Promise<number>): Promise<number> {
    let waiting: ReturnType<typeof setTimeout>;
    const unanswered = new Promise<never>((_, reject) => {
      waiting = setTimeout(() => reject(new Unanswered()), this.patience);
    });

    return Promise.race([save, unanswered]).finally(() => clearTimeout(waiting));
  }
}
