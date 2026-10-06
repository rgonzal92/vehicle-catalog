import { SaveQueue } from './save-queue';

describe('SaveQueue', () => {
  /** The saves sent so far, each waiting to be answered by the test. */
  let sent: {
    edit: string;
    revision: number;
    answer: (revision: number) => void;
    refuse: () => void;
  }[];
  let queue: SaveQueue<string>;

  /** Lets the queue react to an answer. */
  const settled = () => new Promise((resolve) => setTimeout(resolve));

  beforeEach(() => {
    sent = [];
    queue = new SaveQueue<string>(
      (edit, revision) =>
        new Promise<number>((answer, refuse) => sent.push({ edit, revision, answer, refuse })),
      4,
    );
  });

  it('sends an edit made from the revision the catalog was read at', async () => {
    void queue.add('first');
    await settled();

    expect(sent.map(({ edit, revision }) => [edit, revision])).toEqual([['first', 4]]);
  });

  it('keeps one save in flight and sends the next from the revision the first led to', async () => {
    void queue.add('first');
    void queue.add('second');
    void queue.add('third');
    await settled();
    expect(sent).toHaveLength(1);

    sent[0].answer(5);
    await settled();
    expect(sent.map(({ edit, revision }) => [edit, revision])).toEqual([
      ['first', 4],
      ['second', 5],
    ]);

    sent[1].answer(6);
    await settled();
    expect(sent.map(({ edit, revision }) => [edit, revision])).toEqual([
      ['first', 4],
      ['second', 5],
      ['third', 6],
    ]);
  });

  it('tells each edit how its own save went', async () => {
    const first = queue.add('first');
    const second = queue.add('second');
    await settled();

    sent[0].refuse();
    await expect(first).rejects.toBeUndefined();
    await settled();
    sent[1].answer(5);

    await expect(second).resolves.toBeUndefined();
  });
});
