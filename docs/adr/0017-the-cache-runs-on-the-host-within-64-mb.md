# The cache runs on the host, within 64 MB

Redis runs in the host's stack, beside the database, and the host stays a `t4g.small`. Redis may hold 64 MB of values and drops the ones used longest ago past that. It keeps nothing on disk, asks for a password, and listens on no port of the host. With it running, the host used 1,236 MiB of the 1,840 MiB that Linux has to give out, and 429 MiB were available to a new process. If the API's heap, the worker's heap, and the cache all grew to their limits, the host would use about 1,710 MiB. [The measurements](../host-memory-measurements.md) have the rest.

## Considered Options

- **A managed cache, Amazon ElastiCache.** A second thing to pay for by the hour, in a network of its own that the host would have to be let into, for a cache that held 2 MB when it was measured. What the cache holds can be read from PostgreSQL again, so nothing is gained by its outliving the host.
- **A `t4g.medium`, with 4 GiB.** It costs twice as much by the hour. The measured stack fits the smaller machine with everything at its limit, though with about 130 MiB to spare where there were 314.
- **A limit on the API's heap.** The API's heap may grow to 462 MiB and held 77. A limit of half that would give back some 230 MiB of what the host could come to use. It is the first thing to do when the 130 MiB are needed, and was not done here: it changes what the API may do, which is another decision than where the cache runs. It has been taken since, at 256 MiB, which is [a decision of its own](0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md).
- **Another limit for the cache.** The demo's versions take 68 KB each, and the largest catalog there can be takes 2 MB, so 64 MB holds some nine hundred of the demo's versions, or some thirty of the largest. Less would save little: a Redis that holds next to nothing takes 16 MiB by itself.
- **Keeping what it holds on disk.** There is nothing to keep. After a restart the cache is empty, and the first read of each version fills it.

## Consequences

- The limit is in the stack's file, `deploy/compose.yaml`, and an infrastructure test holds the cache to it, to keeping nothing on disk, and to listening on no port.
- Most of what the host uses more than it did is not the cache. The API's and the worker's containers use 59 and 84 MiB more than when they were last measured, in which time the app came to ask a language model, to read documents, and to read through the cache. Of the 180 MiB by which the room at the limits shrank, the cache at its limit takes about 75 and the two Java processes about 100.
- About 130 MiB is what is left with everything at its limit, on a host without swap. A fourth process, a larger limit for the cache, or a Java process that holds much more than was measured means measuring again, and limiting the API's heap first. With the API's heap limited to 256 MiB since, about 345 MiB are left.
- The cache's password is made by Terraform and read by the host from Parameter Store with the stack's other secrets.
- A release starts the cache and does not wait for it, and the API and the worker run whether it does or not.
- Nothing watches the cache's memory. The dashboard shows the share of the host's memory in use, and how the cache answers reads.
