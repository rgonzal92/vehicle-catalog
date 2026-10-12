# Every request still waits for the disk to hold its session's last use

Every request that comes with a session writes when the session was last used, in PostgreSQL, and is not answered before the disk has that write. It stays so. On the deployed host the wait is about 1 ms of a read that the API answers in 20, and the longest one seen took 2.6 ms. The waits of 30 to 170 ms that [the measurements](../cache-measurements.md#the-long-reads) found are of the disk of the machine they were made on, and the host does not have them.

Spring Session does the writing, and its code says how. Its filter sets the session's last use to the present moment whenever it finds a request's session. That marks the session as changed, and at the end of the request the session's row is updated, in a transaction of its own that is committed apart from whatever else the request did. No setting changes this. Of the two there are, one says which attributes are written and the other says when in a request the writing happens, and the app leaves both as they come.

## The wait on the host

On 2026-10-12, from 16:55:14 UTC, the demo author's reads of Compact SUV 2026 v2 were sent to the deployed app one after another for 30 seconds, as they were the day before. For the minute around them, a connection to PostgreSQL on the host looked at what every other connection was waiting for, once every 1.23 ms. No setting was changed and nothing was written there but by the requests themselves.

| | |
|---|---|
| Reads timed | 232 |
| The API's own time for a read, from its log | median 20 ms, three in four within 24, 7 over twice the median, longest 59 |
| Times in the minute that a connection had the disk take what it committed, as PostgreSQL counts them | 247 |
| Updates of a session's row in the minute | 244 |
| Looks | 48,693, of which 213 found a connection waiting for the disk to take what it committed, and 4 found one waiting to hand it over |
| The wait, from those | 262 ms in all, which is 1.06 ms for each of the 247 |
| Waits seen apart | 210, of which one was seen for more than 2 ms, at 2.6 ms, and none for more than 5 |
| What the disk says of itself for the minute | 378 writes in 358 ms, which is 0.95 ms a write |

The two figures of about 1 ms come from different places and agree. The host's disk keeps nothing back that would have to be flushed after a write, so a commit there waits for one write of about a millisecond and for no more.

What limits this:

- **It is one minute, with one person reading.** Nothing says what the wait is when many write at once, or at another hour.
- **The wait is counted and not timed.** PostgreSQL times its own writes only when told to, by a setting that is off on the host and was left off. A wait shorter than the 1.23 ms between two looks is seen once or not at all, so the 1.06 ms is an average that rests on 213 looks, and of single waits only this is known: none was seen to last more than 2.6 ms.
- **The looking ran beside the reads**, on a host with two processors. The reads took a median of 20 ms here and of 16 the day before, without it. Whether the looking is why was not measured.

## Considered Options

- **Only the session's write does not wait.** PostgreSQL lets one transaction say that its commit need not wait, and no other transaction is touched by that. Spring Session can be given it in two ways, both read from its code and neither built:
  - *By the transactions it runs its statements in.* It takes them from the app if the app has a bean named `springSessionTransactionOperations`. One that first says `SET LOCAL synchronous_commit = off` would cover everything Spring Session writes: a new session, a session's update, a session's end, and the clearing away of the expired ones. That is too much. A sign-out, or the ending of a person's sessions when an admin changes their role, could be undone by a failure, and the session would be back with the role it had.
  - *By the statement that updates a session's row*, which the app may replace. With `AND set_config('synchronous_commit', 'off', true) IS NOT NULL` added to its condition, the transaction that updates an existing session does not wait, and a session's beginning and its end wait as before. Tried by hand in PostgreSQL 18, that statement turns the waiting off for its own transaction and for none after it, and leaves it on when no row has the id.

  A request would gain the 1 ms on the host. On the measuring machine it would gain 5 ms of 27.5 at the median and lose the long reads, as the measurements show for the whole database.

  What could be lost, when PostgreSQL or the host stops without warning and at no other time, is what sessions were given in the 0.6 seconds before: three times the 200 ms after which PostgreSQL writes out what did not wait, by its documentation. That is a last use, so that a session could end up to 0.6 seconds sooner than two hours after its last request. It is also whatever else a session's update carries, which is a sign-in: the person who signed in at that moment would have to sign in again. Nothing is left half written, and no edit, submission, or approval is touched.

  What would be built: the statement with its condition, in the app's configuration of sessions; a test that an edit of a catalog, a submission, and an approval still wait, and that a session's update does not; and `npm run reads:even` before and after. The statement is Spring Session's own with one condition more, so each upgrade of Spring Session would mean reading its statement again.

  It is not taken, because on the host there is 1 ms to gain, and what is given for it is a condition in a statement that nobody would guess the purpose of.
- **Writing the last use less often than every request.** If a session's last use is already less than a minute old and nothing else of the session has changed, the request would write nothing. Most requests of someone at work would then do no writing at all, so they would gain the whole wait, on any disk, and the session's row would not be written anew some eight times a second by one person reading. Nothing could be lost in a failure that is not lost today. Instead, a session would end up to a minute sooner than two hours after its last request, every time and not only after a failure.

  Spring Session offers nothing for this. The filter sets the last use in every request, and the session that Spring Session hands out says to nobody else whether anything but that changed. So the app would have its own keeper of sessions around Spring Session's, which remembers what the row held when it was read, watches what a request changes, and decides whether to pass the saving on. It would also have to find and end a person's sessions, as Spring Session's does. That is the most code of the three, in the place where a mistake signs people out or leaves them signed in, for the same 1 ms.
- **PostgreSQL does not wait for any write.** One setting, and what the measurements tried. A saved edit could be lost in a failure after the person was told it was saved, which is why it was not turned on for the measuring either.

## Consequences

- The app is not changed. A request is answered when the disk has its session's last use, and a session ends two hours after its last request.
- On the host that costs a request about 1 ms. A second measurement would be worth making if the host's disk is changed or if many come to use the app at once: each commit waits for a write of its own unless two fall together.
- On the machine the measurements are made on, about one request in five goes on taking 30 to 170 ms longer than the others. The 95th percentiles and the longest times in the measurements say more of that disk than of the app, as they do now, and `npm run reads:even` shows it.
- If the wait is ever worth taking away, the way is the statement that updates a session's row, and not the transactions of everything Spring Session writes: a session's end has to wait for the disk.
- Looking at the host costs nothing that stays: the look made here needs no setting and writes nothing, and can be made again.
