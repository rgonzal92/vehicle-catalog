# The worker runs beside the API on the t4g.small, with a limit on its heap

The host stays a `t4g.small` now that the worker runs on it beside the API. With both in use, the host used 1,074 MiB of the 1,840 MiB that Linux has to give out, and 601 MiB were available to a new process. The worker's heap is limited to 192 MiB, where Java would by itself let it grow to 462 MiB, a quarter of the host's memory. The API's heap had no limit of its own when this was measured, and could grow to those 462 MiB: with both heaps as large as they could get, the host would have used 1,526 MiB. The API's heap has since been [limited to 256 MiB](0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md), and with the two heaps and the cache all at their limits the host would use about 1,495 MiB. [The measurements](../host-memory-measurements.md) have the rest.

## Considered Options

- **A `t4g.medium`, with 4 GiB.** It costs twice as much by the hour. The measured stack left about 300 MiB of the smaller machine unused even with both heaps as large as they could get, and leaves about 345 with the API's heap limited as well, so the larger one would be paid for and not used.
- **No limit on the worker's heap.** The two heaps could then have asked for 924 MiB between them, which the host has not got beside everything else it runs.
- **A machine of its own for the worker.** A second machine to pay for, release to, and watch, for a process that does a few jobs a day and otherwise waits.
- **A native image of the backend, built with GraalVM.** It would start sooner and use less memory than Java does. The build takes longer and has to be told of everything the code reaches by reflection, and what it would save is memory the host has.

## Consequences

- The limit is in the stack's file, `deploy/compose.yaml`, and an infrastructure test holds the worker to having one.
- A job that needs more than 192 MiB of heap is not done. Java ends the worker when its heap is full, as [it ends the API](0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md), and Docker starts it again. The job's message is delivered again, as after any stop of the worker. The job that holds the most today builds the spreadsheet of one catalog, and the worker's heap held 58 MiB after it had built one.
- The worker takes one message from the queue at a time, and asks for the next when that one's job has ended, so a worker that ends in the middle of a job costs that job one of its three deliveries and no other job anything. The queue counts a message as delivered when it hands it over, whether or not its job is ever run, and sets a message aside after three deliveries. A worker that took up to ten in one request, as it did at first, and ended in the middle of the first cost each of the others a delivery, and a job that was behind such a job three times was failed without one try. With several jobs waiting, the worker makes one request to the queue for each, where one request took up to ten; with none waiting it asks as often as before.
- The host still has no swap, and nothing watches its memory: the dashboard shows the share in use, and no alarm is set on it.
- A third process on the host, or an API or a worker that holds much more than was measured, means measuring again before it is added.
