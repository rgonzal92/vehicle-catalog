# The worker runs beside the API on the t4g.small, with a limit on its heap

The host stays a `t4g.small` now that the worker runs on it beside the API. With both in use, the host used 1,074 MiB of the 1,840 MiB that Linux has to give out, and 601 MiB were available to a new process. The worker's heap is limited to 192 MiB, where Java would by itself let it grow to 462 MiB as it lets the API's. If both heaps grew to their limits, the host would use 1,526 MiB. [The measurements](../host-memory-measurements.md) have the rest.

## Considered Options

- **A `t4g.medium`, with 4 GiB.** It costs twice as much by the hour. The measured stack leaves about 300 MiB of the smaller machine unused even with both heaps at their limits, so the larger one would be paid for and not used.
- **No limit on the worker's heap.** The two heaps could then ask for 924 MiB between them, which the host has not got beside everything else it runs.
- **A machine of its own for the worker.** A second machine to pay for, release to, and watch, for a process that does a few jobs a day and otherwise waits.
- **A native image of the backend, built with GraalVM.** It would start sooner and use less memory than Java does. The build takes longer and has to be told of everything the code reaches by reflection, and what it would save is memory the host has.

## Consequences

- The limit is in the stack's file, `deploy/compose.yaml`, and an infrastructure test holds the worker to having one.
- A job that needs more than 192 MiB of heap fails for want of memory. The job that holds the most today builds the spreadsheet of one catalog, and the worker's heap held 58 MiB after it had built one.
- The host still has no swap, and nothing watches its memory: the dashboard shows the share in use, and no alarm is set on it.
- A third process on the host, or an API or a worker that holds much more than was measured, means measuring again before it is added.
