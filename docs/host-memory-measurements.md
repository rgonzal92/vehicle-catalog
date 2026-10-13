# Memory the host's stack uses

How much memory the host uses with the whole stack running on it: Caddy, the API, the worker, the database, the cache, and the CloudWatch agent beside them. The host is a `t4g.small`, which has 2 GiB of memory, of which Linux has 1,840 MiB to give out, and no swap. The decisions on the host's size rest on these numbers: [that it is a `t4g.small`](adr/0009-the-host-is-a-t4g-small-chosen-from-its-measured-memory.md), [that the worker runs on it beside the API](adr/0012-the-worker-runs-beside-the-api-on-the-t4g-small-with-a-limit-on-its-heap.md), [that the cache runs on it too](adr/0017-the-cache-runs-on-the-host-within-64-mb.md), [that the API's heap is limited to 256 MiB](adr/0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md), and [that a PDF is read in a process of its own](adr/0021-a-pdf-is-read-in-a-process-of-its-own-which-java-ends-in-the-workers-place.md).

It was measured three times: on 2026-10-09, before there was a cache; on 2026-10-11, with the cache running; and five hours later, with the API's heap limited to 256 MiB. On 2026-10-13 it was measured once more, for what [the process that reads a PDF](#with-a-process-that-reads-a-pdf) takes while it is there.

Four things limit what the first three say:

- **The API had run for 15 minutes and the worker for 12.** The API had answered about 650 requests in that time: signing in as each demo account, every lineage, version, catalog, and change history read 25 times over, and one catalog made, submitted, approved, and exported. The worker had done two jobs: what follows an approval, and an export. Processes that have run for days and served more people at once will have grown their heaps further, up to the limits below. The second time both had run for 15 minutes, the API had answered about 470 requests of the same kinds, and the worker had done three jobs: the summary of a submission, what follows an approval, and an export. The third time the API had run for 15 minutes and the worker for 18, the API had answered about 430 requests of the same kinds, all of them reads but one, and the worker had done one job, an export.
- **More than the cache came between the first two.** After the first measurement the app came to ask a language model, to read uploaded documents and search them, and to read through the cache. What the two Java processes use more the second time is the sum of all three, and was not taken apart. Between the second and the third came the limit on the API's heap and five hours of smaller changes to the app.
- **The data is the seeded demo.** It holds a handful of catalogs. The database's share grows with what is kept in it.
- **They are one reading.** Nothing here was repeated.

## Results

Measured on 2026-10-09 at 19:20 UTC, on 2026-10-11 at 04:11 UTC with the cache, and on 2026-10-11 at 09:08 UTC with the limit on the API's heap. All figures are in MiB.

### The host as a whole

| | 2026-10-09 | 2026-10-11, with the cache | 2026-10-11, with the limit |
|---|---|---|---|
| Memory in all | 1,840 | 1,840 | 1,840 |
| Used by processes and the kernel | 1,074 | 1,236 | 1,216 |
| Holding files that can be dropped at need | 676 | 528 | 552 |
| Free | 89 | 75 | 71 |
| Available to a new process without swapping | 601 | 429 | 449 |

### Each container

| Container | 2026-10-09 | 2026-10-11, with the cache | 2026-10-11, with the limit |
|---|---|---|---|
| API | 374 | 433 | 396 |
| Worker | 315 | 399 | 415 |
| Database | 97 | 91 | 88 |
| Caddy | 32 | 20 | 20 |
| Cache | | 8 | 7 |

The cache's process held 16 MiB by the count the next table is made with, which includes what it shares with other processes. It kept six Approved versions in 2 MB. With the limit on the API's heap it held 8 MiB by that count, and six versions in 2 MB again.

### What runs beside the containers

| Process | 2026-10-09 | 2026-10-11, with the cache | 2026-10-11, with the limit |
|---|---|---|---|
| CloudWatch agent | 128 | 126 | 129 |
| Docker's daemon | 86 | 81 | 80 |
| The processes that each hold a container, four and then five | 72 | 72 | 72 |
| Systems Manager's agent, with the command it ran to measure this | 61 | 64 | 61 |
| containerd | 38 | 34 | 34 |
| The system's journal | 35 | 22 | 26 |

### The same host at other moments

| | Used | Available |
|---|---|---|
| Without the worker and before the CloudWatch agent was installed, the API having run for 13 hours, on 2026-10-08 | 660 | 1,022 |
| Without the worker, 12 minutes after a release, that day | 727 | 954 |
| With the worker, two minutes after both were started | 1,001 | 675 |
| With the worker, 15 minutes after a release, as above | 1,074 | 601 |
| With the cache, 15 minutes after the stack was started with it, on 2026-10-11, as above | 1,236 | 429 |
| With the limit on the API's heap, 15 minutes after the API was started with it, that day, as above | 1,216 | 449 |

Without the worker the API's container used 365 MiB, the database's 74, and Caddy's 38. So the worker took 347 MiB of what the host has, nearly all of it for its own container.

## The limits of the two heaps

Left to itself, Java gives a heap a quarter of the machine's memory at most, which is 462 MiB here. That was the API's limit the first two times. The worker's heap is limited to 192 MiB in the stack's file.

When this was measured the API's heap held 86 MiB of the 115 it had taken from the system, and the worker's 58 of 87. In the twenty minutes before, the API's heap held between 57 and 92 MiB, as the metric `jvm.heap.used` reported it.

If both heaps grew to their limits, the API would use 347 MiB more than it did and the worker 105 more. The host would then use 1,526 MiB of its 1,840.

## The limits with the cache

The cache may hold 64 MB, which the stack's file sets. On 2026-10-11 it held 2 MB, and its process 16 MiB. Holding all it may, which is 61 MiB, the process would use about 60 MiB more.

The API's heap held 77 MiB that day of the 134 it had taken from the system, and the worker's 77 of 108. If both heaps grew to their limits, the API would use 328 MiB more than it did and the worker 84 more.

With the two heaps and the cache all at their limits, the host would use about 1,710 MiB of its 1,840, which leaves about 130. Before the cache, and before what else the app has come to do, that sum left 314.

## The limits with the API's heap limited

The API's heap is limited to 256 MiB in the stack's file, [chosen from reads of the largest catalog](adr/0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md). In the API's container Java gave its heap's limit as 268,435,456 bytes, and in the worker's as 201,326,592, which are 256 and 192 MiB.

The API's heap held 57 MiB of the 97 it had taken from the system, and the worker's 72 of 132. If both heaps grew to their limits, the API would use 159 MiB more than it did and the worker 60 more. The cache held 2 MB again, and holding all it may would use about 60 MiB more.

With the two heaps and the cache all at their limits, the host would use about 1,495 MiB of its 1,840, which leaves about 345 where there were about 130.

## With a process that reads a PDF

Since 2026-10-13 the worker reads a PDF in [a process of its own](adr/0021-a-pdf-is-read-in-a-process-of-its-own-which-java-ends-in-the-workers-place.md): a third Java process, with a heap of 64 MiB, that is there for as long as that one PDF is read. The host can be measured with it only once it runs the image that holds it. So this was measured after the release that brought it, on 2026-10-13 from 07:15 to 07:20 UTC. That release had started the API at 07:10:23 and the worker at 07:10:49.

| The PDF | The process was there for | Most it had resident, MiB | Least available to the host meanwhile, MiB |
|---|---|---|---|
| 3 pages of text, 2,394 bytes, uploaded by the demo admin | 1.36 s | 76 | 474 |
| 60 pages of text, 37,675 bytes and 248,510 characters, uploaded so | 2.07 s | 78 | 424 |
| A font map that names a range of 2,147,483,647 codes, 1,194 bytes, given to a process started by hand | 1.73 s | 115 | 378 |
| An object stream that holds 15.5 million empty lists, 30,545 bytes, given so | 1.60 s | 113 | 376 |

- **The first two are ordinary PDFs.** Each was ready on the page Documents at its look 7.1 and 6.6 seconds after the click on Upload, with 7 and 177 passages. Their jobs were done in 4.3 and 5.6 seconds after one try, the embedding model asked included. Both were deleted afterwards.
- **The last two are made to fill the process's heap,** which is the most such a process can take: Java ended each with exit code 3 when its heap was full. They were not uploaded to the deployed app. The process was started by hand in the worker's container, as the container's user, with the command line and the empty environment that the worker's own process was seen with.
- **With the two heaps and the cache all at their limits and such a process at its worst, the host would use about 1,610 MiB of its 1,840, which leaves about 230** of the 345 above.
- The host had 529 MiB available before the first upload, 477 before the two started by hand, and 474 after them. At 07:20:03 it used 1,177 MiB and had 488 available, 453 held files that can be dropped at need, and 209 were free. The API's container used 385 MiB then, the worker's 403, the database's 84, Caddy's 16, and the cache's 7.
- The API's and the worker's containers had the start times above, and no restart counted, before and after.

Four things limit what this says:

- **Each figure is one reading,** of an API and a worker that had run for five to nine minutes. Nothing was sent to them for it but the sign-ins and the two uploads, and the worker had read no document since its start. The two heaps were not looked at, so the 345 MiB are those of 2026-10-11.
- **The worker's own process grew over the two jobs.** It had 368 MiB resident before the first PDF and 413 at the most after the second. Its container used 403 MiB afterwards, where it used 415 when the 345 were worked out.
- **The host had all its processor credits,** 576, which is the most a `t4g.small` keeps. With its credits spent, a process would be there for longer. What a process costs the API's answers while both run was not measured.
- **What a process has resident is what Linux counts for it,** with the pages of the files it has in memory, which were not told apart from the rest.

## How it was measured

On the host, through Systems Manager:

- `free -m` for the host as a whole.
- `docker stats --no-stream` for each container.
- `ps -eo rss=,comm=` for the processes beside the containers, the processes of one name added together.
- `java -XX:+PrintFlagsFinal -version`, run in the API's container and in the worker's, for each heap's limit.
- `jcmd 1 GC.heap_info`, run in each of the two, for what its heap held and what it had taken from the system.
- `redis-cli info memory` and `redis-cli dbsize`, run in the cache's container, for what the cache held.

The requests before it were sent from a browser, signed in as the demo author, the demo manager, and the demo admin in turn. On 2026-10-11 the stack had been started with the cache at 03:55 UTC, by the apply that brought the cache to the host. The API was started with its limit at 08:52 UTC, by the apply that brought the limit, and the worker at 08:49 by the release before it. The requests before the third measurement were sent within two minutes of the API's start.

The process that reads a PDF was measured on 2026-10-13 by a command on the host, through Systems Manager, that reads `/proc` and nothing else. Twenty times a second for 80 seconds around each upload, and fifty times a second for 45 seconds around the two started by hand, it wrote down `MemAvailable` from `/proc/meminfo` and, for every `java` process, `VmRSS` and `VmHWM` from its `status`. The most a process had resident is its `VmHWM` at the last look at it, which was the largest `VmRSS` looked at as well, for all four. How long it was there is from the first look that saw it to the last. The two ordinary PDFs were uploaded from a browser signed in as the demo admin. The two made to fill memory were each piped to `docker exec -i` in the worker's container, which ran `env -i` and the process's command line. `free -m` and `docker stats --no-stream` gave the host and the containers at 07:20:03, and CloudWatch's `CPUCreditBalance` the credits.
