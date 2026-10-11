# The API's heap is limited to 256 MiB, chosen from reads of the largest catalog

The API's heap may grow to 256 MiB and no further, where Java would by itself let it grow to a quarter of the host's memory, 462 MiB. The limit is in the host's stack beside the worker's 192 MiB and the cache's 64 MB. It is twice the most the heap held on the host from 2026-10-08 to 2026-10-11, which was 127 MiB by the metric `jvm.heap.used`. By what the host used on 2026-10-11, the two heaps and the cache all at their limits would bring it to about 1,500 MiB of the 1,840 that Linux has to give out, which leaves about 340 where [the cache had left about 130](0017-the-cache-runs-on-the-host-within-64-mb.md). [The measurements](../host-memory-measurements.md) have the host's figures.

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

## Considered Options

- **No limit.** The heap could then grow to 462 MiB, and the host would have about 130 MiB to spare with everything at its limit, without swap.
- **320 MiB.** The workload ran with it as it ran with 256 MiB, no nearer to the run without a limit. It would leave the host about 275 MiB to spare where 256 MiB leaves about 340.
- **A smaller limit.** The largest catalog was read eight at once within 128 MiB and within 96, more slowly each time. The host does not need what a smaller limit would give back, and 256 MiB is twice the most the heap has held there.
- **Having Java end the API when its heap is full.** Java can be told to end the process at the first such error, and Docker would then start the API again, as it does whenever the process ends. An API that answers nothing until someone restarts it would become one that is away for as long as it takes to start. It was not done here: it was not tried, and it would be the worker's to do as well.

## Consequences

- The limit is in the stack's file, `deploy/compose.yaml`, and an infrastructure test holds the API to having one.
- A request that needs more heap than is left fails, and may take the API with it as above. An API in that state stays as it is until it is restarted by hand or by the next release. Whether the alarm on the backend's health goes off for it was not tried.
- The limit is four times the 64 MiB that failed eight reads of the largest catalog at once, and more than twice the 96 MiB that answered them. On the host the heap has held half the limit at most. What would fill it was not looked for: more reads at once than eight, or something that holds more than a read of the largest catalog does.
- The times are from one machine with twelve cores, and the host has two. They say that the limit changes little where it was measured, and nothing certain about the host.
- The local stack has no limit unless one is asked for: `API_JAVA_OPTIONS=-XX:MaxHeapSize=256m` gives its API the host's.
