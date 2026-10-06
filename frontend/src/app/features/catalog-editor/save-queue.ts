/**
 * Sends a catalog's edits one at a time, in the order they were made. Each one names the revision
 * the save before it led to, so the backend can refuse a write that has not seen the latest change.
 */
export class SaveQueue<Edit> {
  /** Settles when the last edit queued so far has been answered, whatever the answer. */
  private last: Promise<void> = Promise.resolve();

  /**
   * @param send saves one edit made from a revision, and answers with the revision it led to
   * @param revision the revision the catalog was read at
   */
  constructor(
    private readonly send: (edit: Edit, revision: number) => Promise<number>,
    private revision: number,
  ) {}

  /** Queues an edit behind the ones before it. The promise settles when its own save does. */
  add(edit: Edit): Promise<void> {
    const saved = this.last.then(async () => {
      this.revision = await this.send(edit, this.revision);
    });
    this.last = saved.catch(() => undefined);

    return saved;
  }
}
