# The contents of Approved versions are read from a cache that is never needed

A read of an Approved version takes its contents from Redis when Redis holds them, and from PostgreSQL otherwise, after which Redis is given them. The contents are the version's trims, regions, offerings, feature rows, cells, and rules, as they read with the labels and the order of its approval. They are kept as JSON by the catalog's id, for a day.

What is kept can never be wrong, so nothing ever has to be taken out of the cache. An Approved version is never edited. [Its labels and its order are recorded when it is approved](0004-labels-freeze-at-approval-validation-stays-live.md), so nothing an admin does to the library changes how it reads. Its offerings and its cells are read in the order of their keys, so it reads the same however PostgreSQL goes about reading their rows, and the cache answers as the database would, order included. An id is never given to a second catalog, also after the demo reset.

Everything that could change is read from PostgreSQL on every read: whether there is such a catalog and whether the person may open it, its name, who approved it, which version is its lineage's current one, and its issues, which validation works out against the library of today. A working copy is never in the cache, in any status.

What decides a change does not read the cache. Submitting and approving read a working copy, and an update from the Approved version reads that version by the labels of today, which is another read and is always of PostgreSQL.

## Considered Options

- **Keeping the whole answer, with the name, the approver, and the issues.** It would save the query for those and the validation as well. The issues change when a global rule or the library does, and the current version changes with every approval, so each of those would have to find and remove entries. A cache that needs that is wrong whenever one of them is forgotten.
- **Keeping working copies.** Every edit would have to remove or replace the entry, in the same moment as the database commits, which Redis takes no part in. A working copy that reads as it was before the last edit would be a bug a person sees.
- **Keeping the contents in the API's own memory.** The worker is a second process and reads the same versions, and a release would empty both. It would also grow the API's heap, which is [the largest thing on a host with little to spare](0012-the-worker-runs-beside-the-api-on-the-t4g-small-with-a-limit-on-its-heap.md).
- **Removing an entry when something changes, in place of a time to live.** Nothing changes. The day is there so that a version no one opens any more does not stay for ever, and so that an entry in a form the app no longer reads goes away by itself.
- **A compressed or a binary form.** The largest catalog there can be is about 2 MB as JSON, of the 64 MB the cache may hold, and JSON can be read by a person who asks Redis for a key.

## Consequences

- When Redis does not answer within a quarter of a second, the read is answered from PostgreSQL and counted as a fallback. From then on no read asks Redis, and each is a fallback, until Redis is known to answer again. The log says once that the cache stopped answering and once that it answers.
- No read reaches Redis for the first time. Making the connection can take longer than a read is given, most of all in an app that has just started, so the app makes it itself: when it has started, and every ten seconds while Redis is not known to answer. It is given two seconds for it, and no one waits for those. The client tries to reach a Redis that has stopped every two seconds, where left to itself it would come to wait half a minute between tries, so a Redis that is back is found within about twelve seconds.
- Without an address for it the app has no cache, starts, and reads everything from PostgreSQL.
- The app is ready whether Redis answers or not. Health names the cache as a part of its own, so with Redis down `/api/health` answers 503 and says which part is down, and `/api/health/readiness` answers 200.
- The key names the form of what is kept, as `approved-contents:1:<catalog id>`. A change to that form, or a migration that changes what an Approved version holds, takes the next number, and what was kept before is then not read and is gone within a day.
- An entry that cannot be read as that form counts as not there, and is replaced.
- The job that follows an approval gives the cache the new version, so the first person to open it finds it there. The demo reset empties the cache of every version. No entry would be wrong without that: it gives back the memory of catalogs that are gone.
- The reads are counted as hits, misses, and fallbacks. The API sends the three counts to CloudWatch, and the worker sends none.
- Reads of one version that the cache does not hold ask PostgreSQL for it one at a time within a process, so reads that arrive together ask once. [What the cache gains](../cache-measurements.md) has that count and the times.
- Redis is asked with Lettuce, the client Spring's Redis support brings, which brings Netty with it.
