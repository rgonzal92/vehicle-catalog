# Memory the host's stack uses

How much memory the host uses with the whole stack running on it: Caddy, the backend, the database, and the CloudWatch agent beside them. The host is a `t4g.small`, which has 2 GiB of memory, of which Linux has 1,840 MiB to give out, and no swap. [The decision on the host's size](adr/0009-the-host-is-a-t4g-small-chosen-from-its-measured-memory.md) rests on these numbers.

Three things limit what they say:

- **The backend had run for 12 minutes.** It had answered about 600 requests in that time: signing in as each demo account, and every lineage, version, catalog, and change history read 25 times over. A backend that has run for days and served more people at once will have grown its heap further, up to the limit below.
- **The data is the seeded demo.** It holds a handful of catalogs. The database's share grows with what is kept in it.
- **They are one reading.** Nothing here was repeated.

## Results

Measured on 2026-10-08 at 17:11 UTC. All figures are in MiB.

### The host as a whole

| | Measured |
|---|---|
| Memory in all | 1,840 |
| Used by processes and the kernel | 727 |
| Holding files that can be dropped at need | 1,001 |
| Free | 111 |
| Available to a new process without swapping | 954 |

### Each container

| Container | Measured |
|---|---|
| Backend | 365 |
| Database | 74 |
| Caddy | 38 |

### What runs beside the containers

| Process | Measured |
|---|---|
| CloudWatch agent | 134 |
| Docker's daemon | 86 |
| The three processes that each hold a container | 60 |
| containerd | 55 |
| Systems Manager's agent | 29 |
| The system's journal | 27 |

### The same host at other moments that day

| | Used | Available |
|---|---|---|
| Before the CloudWatch agent was installed, the backend having run for 13 hours | 660 | 1,022 |
| With the agent, two minutes after a release | 683 | 998 |
| With the agent, 12 minutes after that release, as above | 727 | 954 |

## The backend's limit

Java gives the backend's heap a quarter of the machine's memory at most, which is 462 MiB here. In the minutes before this was measured the heap held between 56 and 92 MiB, as the metric `jvm.heap.used` reported it.

If the heap grew to its limit, and none of what the backend used when measured were counted as heap, the backend would use 462 MiB more than it did. The host would then use 1,189 MiB of its 1,840.

## How it was measured

On the host, through Systems Manager:

- `free -m` for the host as a whole.
- `docker stats --no-stream` for each container.
- `ps -eo rss=,comm=` for the processes beside the containers, the processes of one name added together.
- `java -XX:+PrintFlagsFinal -version`, run in the backend's container, for the heap's limit.

The requests before it were sent from a browser signed in as the demo author, one after another.
