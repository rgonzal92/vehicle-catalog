# The host is a t4g.small, chosen from its measured memory

The host stays a `t4g.small`, with 2 GiB of memory. With Caddy, the backend, the database, and the CloudWatch agent running, it used 727 MiB of the 1,840 MiB that Linux has to give out, and 954 MiB were available to a new process. If the backend's heap grew to the limit Java sets for it, the host would use 1,189 MiB. [The measurements](../host-memory-measurements.md) have the rest.

## Considered Options

- **A `t4g.medium`, with 4 GiB.** It costs twice as much by the hour. The measured stack leaves a third of the smaller machine's memory unused even with the backend's heap at its limit, so the larger one would be paid for and not used.

## Consequences

- The host has no swap. Memory that runs out is taken back by Linux stopping a process, and the largest one is the backend.
- The backend's heap is limited to a quarter of the machine's memory because Java sets that by itself. Nothing in the stack sets a limit of its own on any container.
- The dashboard shows the share of the host's memory in use, and nothing watches it: there is no alarm on it.
- The stack as measured fits. A second process of the backend's size would not fit if both filled their heaps, so the host is measured again before one is added.
