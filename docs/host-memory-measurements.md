# Memory the host's stack uses

How much memory the host uses with the whole stack running on it: Caddy, the API, the worker, the database, and the CloudWatch agent beside them. The host is a `t4g.small`, which has 2 GiB of memory, of which Linux has 1,840 MiB to give out, and no swap. The decisions on the host's size rest on these numbers: [that it is a `t4g.small`](adr/0009-the-host-is-a-t4g-small-chosen-from-its-measured-memory.md), and [that the worker runs on it beside the API](adr/0012-the-worker-runs-beside-the-api-on-the-t4g-small-with-a-limit-on-its-heap.md).

Three things limit what they say:

- **The API had run for 15 minutes and the worker for 12.** The API had answered about 650 requests in that time: signing in as each demo account, every lineage, version, catalog, and change history read 25 times over, and one catalog made, submitted, approved, and exported. The worker had done two jobs: what follows an approval, and an export. Processes that have run for days and served more people at once will have grown their heaps further, up to the limits below.
- **The data is the seeded demo.** It holds a handful of catalogs. The database's share grows with what is kept in it.
- **They are one reading.** Nothing here was repeated.

## Results

Measured on 2026-10-09 at 19:20 UTC. All figures are in MiB.

### The host as a whole

| | Measured |
|---|---|
| Memory in all | 1,840 |
| Used by processes and the kernel | 1,074 |
| Holding files that can be dropped at need | 676 |
| Free | 89 |
| Available to a new process without swapping | 601 |

### Each container

| Container | Measured |
|---|---|
| API | 374 |
| Worker | 315 |
| Database | 97 |
| Caddy | 32 |

### What runs beside the containers

| Process | Measured |
|---|---|
| CloudWatch agent | 128 |
| Docker's daemon | 86 |
| The four processes that each hold a container | 72 |
| Systems Manager's agent, with the command it ran to measure this | 61 |
| containerd | 38 |
| The system's journal | 35 |

### The same host at other moments

| | Used | Available |
|---|---|---|
| Without the worker and before the CloudWatch agent was installed, the API having run for 13 hours, on 2026-10-08 | 660 | 1,022 |
| Without the worker, 12 minutes after a release, that day | 727 | 954 |
| With the worker, two minutes after both were started | 1,001 | 675 |
| With the worker, 15 minutes after a release, as above | 1,074 | 601 |

Without the worker the API's container used 365 MiB, the database's 74, and Caddy's 38. So the worker took 347 MiB of what the host has, nearly all of it for its own container.

## The limits of the two heaps

Java gives the API's heap a quarter of the machine's memory at most, which is 462 MiB here. The worker's heap is limited to 192 MiB in the stack's file.

When this was measured the API's heap held 86 MiB of the 115 it had taken from the system, and the worker's 58 of 87. In the twenty minutes before, the API's heap held between 57 and 92 MiB, as the metric `jvm.heap.used` reported it.

If both heaps grew to their limits, the API would use 347 MiB more than it did and the worker 105 more. The host would then use 1,526 MiB of its 1,840.

## How it was measured

On the host, through Systems Manager:

- `free -m` for the host as a whole.
- `docker stats --no-stream` for each container.
- `ps -eo rss=,comm=` for the processes beside the containers, the processes of one name added together.
- `java -XX:+PrintFlagsFinal -version`, run in the API's container and in the worker's, for each heap's limit.
- `jcmd 1 GC.heap_info`, run in each of the two, for what its heap held and what it had taken from the system.

The requests before it were sent from a browser, signed in as the demo author, the demo manager, and the demo admin in turn.
