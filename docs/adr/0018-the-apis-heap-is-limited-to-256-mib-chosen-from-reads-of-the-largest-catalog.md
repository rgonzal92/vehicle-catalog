# The API's heap is limited to 256 MiB, chosen from reads of the largest catalog

The API's heap may grow to 256 MiB and no further, where Java would by itself let it grow to a quarter of the host's memory, 462 MiB. The limit is in the host's stack beside the worker's 192 MiB and the cache's 64 MB. It is twice the most the heap held on the host from 2026-10-08 to 2026-10-11, which was 127 MiB by the metric `jvm.heap.used`. Measured on the host with the limit, the two heaps and the cache all at their limits would bring it to about 1,495 MiB of the 1,840 that Linux has to give out, which leaves about 345 where [the cache had left about 130](0017-the-cache-runs-on-the-host-within-64-mb.md). [The measurements](../host-memory-measurements.md) have the host's figures.

## What it was chosen from

The workload that [measures what the cache gains](../cache-measurements.md), `npm run cache:measure`, reads the largest catalog there can be, 500 feature rows by 96 offerings, one after another and eight at once. Without a cache each of those reads builds the catalog from the database, which is when the heap holds the most. It was run on 2026-10-11 against the local stack, once for each of four limits on the API's heap: none, 256 MiB, 320 MiB, and 462 MiB, which is what the host had until now. On the machine that measured, no limit means 8 GB, a quarter of its 32 GB, which the host never had.

- **No request failed in any of the four runs,** and PostgreSQL was sent the same statements in each.
- **One after another, the limit costs nothing that shows.** Each of the twelve medians with 256 MiB is within 7% of the one without a limit.
- **Eight at once, the three runs with a limit stand together, a little above the run without one.** The medians of the largest version are below, in milliseconds. With 256 MiB they are 5 to 18% above those without a limit and within 6% of those with 462 MiB. With 320 MiB they are 4 to 14% above those without a limit.
- **Eight at once, the other medians say little.** Of the twelve with 256 MiB, five are more than a tenth above the run without a limit, and so are five of the twelve with 320 MiB, though not the same five. None is more than a tenth above the run with 462 MiB. A seeded version with the cache stopped took 36 ms without a limit, 36 with 256 MiB, 78 with 320, and 44 with 462. Medians of eight at once have differed by as much between two runs of the same code.

| The largest version, eight at once | No limit | 256 MiB | 320 MiB | 462 MiB |
|---|---|---|---|---|
| Cache off | 681 | 734 | 726 | 773 |
| On and empty | 627 | 656 | 653 | 659 |
| On and holding it | 137 | 162 | 149 | 163 |
| Cache stopped | 669 | 764 | 765 | 766 |

So with 256 MiB the largest catalog was read as it was with the 462 MiB the host had. What reads eight at once gained from a heap of 8 GB, the host could not give them.

How little is too little was tried apart from the workload, with the cache off: three reads of the largest version one after another, then eight at once three times, with smaller and smaller limits.

| Limit | Eight at once | Median of the eight at once |
|---|---|---|
| 256 MiB | All answered | 0.75 to 0.80 s |
| 128 MiB | All answered | 0.92 to 0.97 s |
| 96 MiB | All answered | 1.3 to 1.4 s |
| 64 MiB | Failed, as below | |

With 256 MiB, Java's own log of its collections has 222 of them while those 27 reads were answered, none of them a full collection, and the longest held the API up for 7 ms.

## What the API does when its heap is full

With 64 MiB the three reads one after another were answered. Then:

- Of the first eight at once, four were answered 500 and four 200, and the last answer came after 40 seconds.
- Of the next eight, four were answered 500 and two 200, and two broke off after 70 seconds without a whole answer.
- The eight after that were not answered at all, and neither was anything else. Docker's check of the API's readiness, asked inside its container every few seconds, failed 64 times running in the seven minutes the API was left as it was.

The log has `java.lang.OutOfMemoryError: Java heap space` for the requests answered 500, in the threads that answer requests, and once in the thread that hands arriving requests to them, Tomcat's poller. That thread ended and was not started again: of Tomcat's threads the one that accepts connections and the ten that answer were left, and nothing that arrived reached them. The process went on running and used next to no processor time. The kernel did not end it, and Docker marked the container unhealthy and left it running. On the host Docker starts a container again when its process ends, and this process had not ended.

So neither of the two things one might hope for happened. The requests that needed more did fail by themselves at first, and the API answered others meanwhile, slowly. But the error falls on whichever thread asks for memory next, and when that was one the API cannot do without, the API stopped answering for good and nothing started it again. This was tried once. Which thread the error falls on is chance, and another time the API may go on answering.

## Java ends an API or a worker whose heap is full

Since 2026-10-12 the API and the worker are started with `-XX:+ExitOnOutOfMemoryError` beside their heap limits. Java then ends the process at the first such error, and Docker starts it again, as it starts any container of the host's stack whose process ends.

It was tried once that day on the local stack, with the cache off, a limit of 64 MiB, the flag, and for that trial the host's `restart: unless-stopped` on the API, which the local stack does not have:

- Three reads of the largest version one after another were answered, in 380, 181, and 145 ms.
- Of the first eight at once none was answered by the API: the web server in front of it answered each 502 within half a second. Java had written `Terminating due to java.lang.OutOfMemoryError: Java heap space` and ended with exit code 3.
- Docker started the container again 0.2 seconds after its process had ended. Its restart count went from 0 to 1.
- The same read, asked again four times a second, was answered 502 until the API had started, and 200 from 9.3 seconds after the process had ended. It was sent with the session of before, which the database keeps. The API said it was ready when asked next.

So an API that would have answered nothing for as long as it was left alone was away for 9.3 seconds, on a machine with twelve cores. A start there logged 7.7 seconds. On the host, which has two cores, the API's start of that day logged 20.1 seconds, so there it would be away for longer; how long was not tried.

## What is asked while the API is away waits for it

Since 2026-10-12 the host's web server, Caddy, keeps a request that finds no API to connect to and asks the API again four times a second, for 26 seconds (`lb_try_duration` in `deploy/Caddyfile`). Such a request never reached an API, so a write is kept as a read is. A request that did reach the API, and lost it, is answered 502 at once and is not sent a second time.

The 26 seconds are from the host's log, which has 113 starts of the API from 2026-10-08 to 2026-10-12. The last twenty logged 18.4 to 21.8 seconds, and their processes had run for 19.6 to 23.2 seconds by then, 21.2 at the median. CloudFront, which is in front of Caddy, waits 30 seconds for an answer. A try may wait three seconds to connect, so with 26 seconds Caddy has answered after 29.25 at the latest.

It was tried three times that day on the local stack, as the flag was: no cache, a limit of 64 MiB, the flag, the host's `restart: unless-stopped`, and the local web server passing requests on with the lines of the host's file. Each time eight reads of the largest version were sent at once, and as soon as Docker said the process had ended, three more requests were sent once each: the same read, a small read, and a write that creates a working copy.

| | Each of the eight at once | The API ended | The same read, asked once | The small read | The write |
|---|---|---|---|---|---|
| Trying again as Caddy does unless told otherwise, for 27 seconds | 502 after 27.2 s | 4 times in 23 s | 502 after 27.1 s | 200 after 7.1 s | 201 after 7.1 s |
| Nothing sent again that reached the API, 26 seconds | Seven 502 within 0.4 s, one 200 after 8.7 s | Once | 200 after 8.2 s | 200 after 7.7 s | 201 after 7.8 s |
| And a connection of its own for every request, which is what the host has | 502 within 0.6 s | Once | 200 after 7.0 s | 200 after 6.7 s | 201 after 6.7 s |

- **Asking a read again that the API was answering made things worse.** Unless told otherwise, Caddy sends a `GET` again when its connection breaks. The eight reads had filled the heap together, and were sent again together each time the API had started: it ended again 0.8 seconds after each of its next three starts, four times in all, and had started for good 30.7 seconds after it had first ended, where that takes 7 when it ends once. The small read and the write were answered in between.
- **Telling Caddy to send nothing again was not enough.** One of the eight was answered 200 all the same. Its connection had carried a request before, and Go's HTTP client, which Caddy passes requests on with, sends a read again by itself when such a connection breaks before an answer has begun. Caddy then found no API to connect to and kept the request. A write is never sent again that way, unless it carries an `Idempotency-Key`, and the app sends none.
- **With a connection of its own for every request, all eight were answered 502 and the API ended once.** The three requests asked while it was away were each asked once and answered 6.7 to 7.0 seconds later, the last of them 7.3 seconds after the process had ended. The write was saved once. Forty small reads one after another took 5 ms at the median that way, and 8 at the most.
- **While the API was away Caddy saw three failures,** and kept the request through each: for a moment after the process had ended the name `backend` was not known, and looking it up ran into the three seconds a try has to connect, or was answered that there is no such host; after that the connection was refused, until the API listened.

So on that machine a request waited the 7 seconds the API took to start. On the host it would wait about 22, by the starts in its log, which was not tried: the API was not ended there on purpose.

A release replaces the API's container on the host, and one was watched there: the release of 2026-10-13 at 01:54 UTC. The host's Caddyfile had `lb_try_duration 26s`, and its Caddy was 2.11.6, since the stack's file reaches the host with an apply and none had been made since the stack was set to 2.11.7; neither was looked at on the host. A request for `/api/health/readiness` was started through the site every second, each waiting up to 35 seconds for its answer.

- The one asked at 01:54:30.2 UTC was answered 200 by the API that was being replaced, within 0.09 seconds.
- The 25 asked from 01:54:31.2 to 01:54:55.2 were all answered at 01:54:55.5, which is 24.4 seconds after the first of them was asked and 0.3 after the last. So the host was without an API for 24.4 seconds, or up to a second more, since one request was asked a second.
- They were answered 503, as was one asked a second later, and the next 200, at 01:54:57.2. The 503 is taken to be the new API's own answer to that question, since it listens a moment before it says it is ready and Caddy answers 502 when it gives up: that is concluded, not seen in the host's log. None was answered 502 or 504, and none went without an answer.
- What the API's start logged in that release was not read from the host's log.

So in that release Caddy kept every request until the new API listened, with between 0.7 and 1.6 seconds of its 26 to spare. The release of 2026-10-12 at 18:01 UTC was asked the same question once a second, with the Caddyfile as it was before, and answered 502 from 18:01:43 to 18:02:08 and 503 once at 18:02:09: counted the same way it left the host without an API for 26 to 27 seconds, timed to the second. A release that long may not be over in Caddy's time, and what is asked in its first second or two would then be answered 502. That is reasoned from the two releases and was not seen.

## Considered Options

- **No limit.** The heap could then grow to 462 MiB, and the host would have about 130 MiB to spare with everything at its limit, without swap.
- **320 MiB.** The workload ran with it as it ran with 256 MiB, no nearer to the run without a limit. It would leave the host about 280 MiB to spare where 256 MiB leaves about 345.
- **A smaller limit.** The largest catalog was read eight at once within 128 MiB and within 96, more slowly each time. The host does not need what a smaller limit would give back, and 256 MiB is twice the most the heap has held there.
- **Having Java end the API when its heap is full.** Java can be told to end the process at the first such error, and Docker would then start the API again, as it does whenever the process ends. An API that answers nothing until someone restarts it would become one that is away for as long as it takes to start. It was not done with the limit, and was taken the day after for the API and the worker both, as above.
- **Sending a read again that the API was answering when it ended.** Caddy does so unless told otherwise, and an innocent read would then be answered where it is now lost. It was tried and not taken: the reads that had filled the heap were sent again with it and filled it again, as above.

## Consequences

- The limit and the flag are in the stack's file, `deploy/compose.yaml`, and an infrastructure test holds the API and the worker to having both.
- A request that needs more heap than is left ends the API. What happens to the other requests depends on whether they had reached it:
  - **Held:** a request that arrives while the API is away, a read or a write, waits for it and is answered, as long as the API is back within 26 seconds. The same holds while a release replaces the API: in the one release watched with a request asked every second, the host was without an API for 24.4 seconds and every request was kept and answered, as above. The catalog editor waits less long than Caddy does: it gives up an edit that has gone unanswered for 20 seconds (`SAVE_PATIENCE` in `frontend/src/app/core/catalogs.ts`), shows "No answer says whether this change was saved", and sends no further edit, as it does after a 502. On the host, where the API would be away for about 22 seconds, an edit made in the first seconds after the API has ended is given up that way before the API is back. Whether such an edit is saved all the same once the API has started was not tried.
  - **Asked again:** nothing that reached the API. Caddy asks only for a connection again.
  - **Still lost:** every request the API was answering when it ended, which is answered 502 at once. A write among them may have been saved or not, and the person sees "Something went wrong" and tries again by hand. A request that has found no API for 26 seconds is answered 502 as well, which is 26 to 29.25 seconds after it was asked.
- **Caddy 2.11.6 could end as a whole, and with it every request it was keeping. The stack runs 2.11.7, which does not.** A request to Caddy 2.11.6 over HTTP/2 that carried a body, and that the API answered before Caddy had passed all of the body on, now and then ended Caddy's process: it went on reading the body once the answer was done, and stopped with `panic: runtime error: invalid memory address or nil pointer dereference` in `IdleTimeoutReader.Read`, which that version put around every request's body (caddyserver/caddy#8101). The write itself had been answered. Every other request Caddy was answering or keeping then ended without a status, and Docker started Caddy again. `deploy/test/check.sh` asks over HTTP/2, and its stand-in for the API answers a write without reading it, so it meets this. The one run of the check that left a read without an answer beside a write answered 200 kept no log, and no round since gave just that: that it was this is concluded, not seen. With 2.11.6, Caddy ended in 16 of 54 batches of forty writes sent at once on one machine; with 2.11.7, in none of 100. The check now sends 400 writes, forty at a time, and holds that Caddy has not ended. Whether Caddy ever ended this way on the host was not looked at, nor whether CloudFront speaks HTTP/2 to it, which is where Caddy's notes on 2.11.7 say it ends.
- The 26 seconds leave little room on either side. The slowest of the last twenty starts on the host left about two of them, and a start logged about 16 seconds on 2026-10-09 and about 20 on 2026-10-12. The one release watched left between 0.7 and 1.6 seconds. A request that connects in the last of them has four seconds to be answered in before CloudFront gives up, and a write that takes longer would be saved and answered 504. Longer than this takes CloudFront waiting longer, which was not changed.
- Caddy opens a connection to the API for every request, where it kept connections for two minutes. Both are on the host, and no difference showed in the trial.
- The dashboard counts by the hour how often the API and the worker have each started, from the line each writes to the log once it has started. A restart is one start more there, among those of the releases, and the chart does not say what ended the process. No alarm watches for one, and none was tried.
- A worker whose heap is full ends the same way, and the message of the job it was doing is delivered again, as after any stop. The worker then never gets to write down that the job failed for want of memory, which it wrote down while the error was one it could catch.
- The limit is four times the 64 MiB that failed eight reads of the largest catalog at once, and more than twice the 96 MiB that answered them. On the host the heap has held half the limit at most. What would fill it was not looked for: more reads at once than eight, or something that holds more than a read of the largest catalog does.
- The times are from one machine with twelve cores, and the host has two. They say that the limit changes little where it was measured, and nothing certain about the host.
- The local stack has no limit unless one is asked for: `API_JAVA_OPTIONS=-XX:MaxHeapSize=256m` gives its API the host's. Its API is not started again when its process ends, either, and its web server keeps no request.
