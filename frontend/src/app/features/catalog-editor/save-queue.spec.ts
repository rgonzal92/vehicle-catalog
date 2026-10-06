import { HttpErrorResponse } from '@angular/common/http';
import { failureOf, NotSent, SaveQueue } from './save-queue';

describe('SaveQueue', () => {
  /** The saves sent so far, each waiting to be answered by the test. */
  let sent: {
    edit: string;
    revision: number;
    answer: (revision: number) => void;
    fail: (error: unknown) => void;
  }[];
  let queue: SaveQueue<string>;

  /** Lets the queue react to an answer. */
  const settled = () => new Promise((resolve) => setTimeout(resolve));

  const refusal = (status: number) => new HttpErrorResponse({ status });

  const whatWasSent = () => sent.map(({ edit, revision }) => [edit, revision]);

  beforeEach(() => {
    sent = [];
    queue = new SaveQueue<string>(
      (edit, revision) =>
        new Promise<number>((answer, fail) => sent.push({ edit, revision, answer, fail })),
      4,
    );
  });

  it('sends an edit made from the revision the catalog was read at', async () => {
    void queue.add('first');
    await settled();

    expect(whatWasSent()).toEqual([['first', 4]]);
    expect(queue.stopped()).toBeNull();
  });

  it('keeps one save in flight and sends the next from the revision the first led to', async () => {
    void queue.add('first');
    void queue.add('second');
    void queue.add('third');
    await settled();
    expect(sent).toHaveLength(1);

    sent[0].answer(5);
    await settled();
    expect(whatWasSent()).toEqual([
      ['first', 4],
      ['second', 5],
    ]);

    sent[1].answer(6);
    await settled();
    expect(whatWasSent()).toEqual([
      ['first', 4],
      ['second', 5],
      ['third', 6],
    ]);
  });

  it('says whether an edit changed the catalog, which it did not when the revision stays', async () => {
    const first = queue.add('first');
    const second = queue.add('second');
    await settled();

    sent[0].answer(5);
    await settled();
    sent[1].answer(5);

    await expect(first).resolves.toBe(true);
    await expect(second).resolves.toBe(false);
  });

  it('is idle only while no edit is waiting for its outcome', async () => {
    expect(queue.idle()).toBe(true);

    void queue.add('first');
    void queue.add('second').catch(() => undefined);
    expect(queue.idle()).toBe(false);
    await settled();
    sent[0].answer(5);
    await settled();
    expect(queue.idle()).toBe(false);
    sent[1].fail(refusal(422));
    await settled();

    expect(queue.idle()).toBe(true);
  });

  it('says when every edit queued so far has had its outcome', async () => {
    const first = queue.add('first').catch(() => undefined);
    let idle = false;
    void queue.whenIdle().then(() => (idle = true));
    await settled();
    expect(idle).toBe(false);

    sent[0].fail(refusal(412));
    await first;
    await settled();

    expect(idle).toBe(true);
  });

  it('drops an edit the backend turns down and goes on from the same revision', async () => {
    const first = queue.add('first');
    const second = queue.add('second');
    await settled();

    sent[0].fail(refusal(422));
    await expect(first).rejects.toBeInstanceOf(HttpErrorResponse);
    await settled();
    expect(whatWasSent()).toEqual([
      ['first', 4],
      ['second', 4],
    ]);
    sent[1].answer(5);

    await expect(second).resolves.toBe(true);
    expect(queue.stopped()).toBeNull();
  });

  it('stops for good after a revision conflict, sending neither the edits queued nor new ones', async () => {
    const first = queue.add('first');
    const second = queue.add('second');
    await settled();

    sent[0].fail(refusal(412));

    await expect(first).rejects.toBeInstanceOf(HttpErrorResponse);
    await expect(second).rejects.toBeInstanceOf(NotSent);
    await expect(queue.add('third')).rejects.toBeInstanceOf(NotSent);
    expect(queue.stopped()).toBe('conflict');
    expect(whatWasSent()).toEqual([['first', 4]]);
  });

  it('stops when the outcome of a save is unknown, and does not send it again', async () => {
    const first = queue.add('first');
    await settled();

    sent[0].fail(refusal(0));

    await expect(first).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(queue.stopped()).toBe('uncertain');
    await expect(queue.add('second')).rejects.toBeInstanceOf(NotSent);
    expect(sent).toHaveLength(1);
  });

  it('stops when the catalog can no longer be edited', async () => {
    const first = queue.add('first');
    await settled();

    sent[0].fail(refusal(409));

    await expect(first).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(queue.stopped()).toBe('closed');
  });
});

describe('failureOf', () => {
  it.each([
    [412, 'conflict'],
    [409, 'closed'],
    [404, 'closed'],
    [422, 'rejected'],
    [400, 'rejected'],
    [403, 'rejected'],
    [428, 'rejected'],
    [0, 'uncertain'],
    [500, 'uncertain'],
    [503, 'uncertain'],
    // An answer that says the edit was saved, but cannot be read.
    [200, 'uncertain'],
  ])('reads a response with status %i as %s', (status, failure) => {
    expect(failureOf(new HttpErrorResponse({ status }))).toBe(failure);
  });

  it('reads anything that is no response, such as a save given up on, as uncertain', () => {
    expect(failureOf(new Error('No answer came in time.'))).toBe('uncertain');
  });
});
