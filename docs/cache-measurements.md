# What the cache gains

How long reads of Approved versions take with the cache and without it, and what PostgreSQL is sent for them. [The cache keeps the contents of Approved versions](adr/0016-the-contents-of-approved-versions-are-read-from-a-cache-that-is-never-needed.md): everything else a read answers with is read from PostgreSQL every time. These are measurements under a made-up load, on one machine and on generated data. They say what the cache saves in the app as it is built, and nothing certain about the deployed one: of the deployed host there are the times of [half a minute of reads](#on-the-deployed-host) and of a handful of requests, and no more. The handful is from the look at what the deployed app does when its cache is stopped, which is [at the end](#the-cache-stopped-on-the-host).

Five things limit what they say:

- **Everything ran on one machine.** The program that sent the requests, the web server, the API, PostgreSQL, and Redis shared a processor with twelve cores and had no network between them. The host has two processors, which it may use in full only for a while, and runs the worker and an agent beside them. The API's heap is limited to 256 MiB there and was not limited here.
- **One person read, and nobody wrote.** Every request was the same person's, and nothing else used the app. The API had answered the same requests before any was timed.
- **The data is generated or seeded.** The largest catalog there can be is made by the app for measuring, and the demo has no catalog near its size. The seeded catalog measured has 151 feature rows and 7 offerings.
- **The columns were measured one after the other, always in the same order.** The API is started for the first column and started again for the second, and not again after that. So by the third column it has answered some two hundred requests since its start, and by the fourth some four hundred, where the first two begin with an API that has just started. The first column and the last both read PostgreSQL for every request, and they do not take the same.
- **They are two runs.** The tables are of the second, and the medians of both are below them. Both are of 2026-10-12 and of the code as it was that day, in which the cells of a catalog are read by a mapper written for them. The tables this document had before were of 2026-10-10, when the cells were made into records by handing Spring the record's class, and their medians are [below](#as-they-were-on-2026-10-10) for comparison.

## Results

Times are in milliseconds, from sending a request until its whole answer had arrived.

The four columns are the app without an address for the cache; with the cache, when it held nothing of what the request reads; with the cache holding it; and with the cache stopped while the requests were sent.

### One after another, 30 requests

| | Cache off | On and empty | On and holding it | Cache stopped |
|---|---|---|---|---|
| Opening the largest Approved version | median 96, 95th percentile 162, longest 166 | median 128, 95th percentile 143, longest 146 | median 83, 95th percentile 106, longest 110 | median 102, 95th percentile 133, longest 140 |
| Opening a seeded Approved version | median 16, 95th percentile 19, longest 19 | median 24, 95th percentile 69, longest 71 | median 9.6, 95th percentile 11, longest 11 | median 11, 95th percentile 15, longest 15 |
| Comparing two versions of one lineage | median 18, 95th percentile 26, longest 27 | median 26, 95th percentile 62, longest 63 | median 12, 95th percentile 13, longest 14 | median 15, 95th percentile 19, longest 20 |

### 8 at once, 3 times

| | Cache off | On and empty | On and holding it | Cache stopped |
|---|---|---|---|---|
| Opening the largest Approved version | median 199, 95th percentile 250, longest 256 | median 226, 95th percentile 299, longest 309 | median 160, 95th percentile 202, longest 212 | median 172, 95th percentile 209, longest 217 |
| Opening a seeded Approved version | median 21, 95th percentile 25, longest 26 | median 26, 95th percentile 33, longest 33 | median 16, 95th percentile 21, longest 22 | median 16, 95th percentile 22, longest 24 |
| Comparing two versions of one lineage | median 23, 95th percentile 30, longest 30 | median 32, 95th percentile 39, longest 41 | median 18, 95th percentile 25, longest 26 | median 21, 95th percentile 26, longest 28 |

### Statements PostgreSQL was sent for one request

| | Cache off | On and empty | On and holding it | Cache stopped |
|---|---|---|---|---|
| Opening the largest Approved version | 14 | 14 | 8 | 14 |
| Opening a seeded Approved version | 14 | 14 | 8 | 14 |
| Comparing two versions of one lineage | 20 | 20 | 8 | 20 |

### The medians of both runs

The first run's median, then the second's.

| | Cache off | On and empty | On and holding it | Cache stopped |
|---|---|---|---|---|
| The largest version, one after another | 117, 96 | 123, 128 | 82, 83 | 100, 102 |
| A seeded version, one after another | 16, 16 | 21, 24 | 9.5, 9.6 | 9.9, 11 |
| A comparison, one after another | 17, 18 | 24, 26 | 11, 12 | 14, 15 |
| The largest version, 8 at once | 223, 199 | 220, 226 | 163, 160 | 180, 172 |
| A seeded version, 8 at once | 20, 21 | 27, 26 | 16, 16 | 17, 16 |
| A comparison, 8 at once | 23, 23 | 31, 32 | 18, 18 | 19, 21 |

For a seeded version and a comparison the medians of the two runs are close: none differs by more than 3 ms. For the largest version without a cache they are not: 117 in one run and 96 in the other, one after another, and 223 and 199 eight at once. That is the figure the cache's gain at that size is counted from, so the gain is given below as from one figure to another, and not as one. The statements were the same in both runs.

### As they were on 2026-10-10

The medians of the tables this document had before, one after another, beside those of the second run of 2026-10-12, which are in the first table above. Between the two, the cells of a catalog came to be [read by a mapper written for them](largest-catalog-measurements.md). What else changed in the app in those two days was not taken apart from it.

| | Cache off, 2026-10-10 | Cache off, now | On and holding it, 2026-10-10 | On and holding it, now |
|---|---|---|---|---|
| Opening the largest Approved version | 558 | 96 | 68 | 83 |
| Opening a seeded Approved version | 34 | 16 | 6.8 | 9.6 |
| Comparing two versions of one lineage | 46 | 18 | 8.4 | 12 |

## What they say

**The cache saves the reading of the contents, which is no longer most of a read.** With the cache holding it, the largest version opens in a median of 83 ms, against 96 without a cache; the first run has 82 against 117. So the cache saves between 13 and 35 ms of about a hundred there, where on 2026-10-10 it saved 490 of 558. A seeded version opens in 9.6 ms against 16, and a comparison in 12 against 18. PostgreSQL is sent 8 statements where it was sent 14: the six that read the contents are not sent. A comparison reads two versions, and is sent 8 where it was sent 20.

**Held against the reads with the cache stopped, it saves less.** Those read PostgreSQL for everything too, with the same statements, and were sent after the API had answered some hundreds of requests, as the reads from the cache were. The largest version opens in 83 ms against 102, a seeded one in 9.6 against 11, and a comparison in 12 against 15; the first run has 82 against 100, 9.5 against 9.9, and 11 against 14. That is 18 or 19 ms at the largest size, 3 ms for a comparison, and about 1 ms for a seeded version. Why a read without the cache takes less in the last column than in the first, for the seeded version and the comparison in both runs, was not looked into, so which of the two the cache is to be held against is not settled. By either, the gain is small.

**What is left is not the cache's to save.** The 83 ms that remain for the largest version go to finding the session and the person, to what describes the catalog, to validating it against the library of today, and to writing 1.8 million characters of JSON. They were not taken apart. On 2026-10-10 they were 68 ms, and a read from the cache took less then at the other two sizes as well. Why it takes more now was not looked into.

**A read that finds the cache empty costs more than one without a cache.** It reads PostgreSQL as before and then gives the cache what it read, which for the largest version is 2 MB: a median of 128 against 96, and 123 against 117 in the first run. A seeded version takes 24 against 16, and a comparison 26 against 18. So at the two smaller sizes a read that fills the cache loses about as much as a read that finds it filled gains. Not all of that need be the filling: to empty the cache, the script runs two Docker commands before every request of this column and of no other, so the API waits between its requests here, and what part of the difference that is was not measured. The job that follows an approval gives the cache a new version before anyone opens it, so the reads that pay this are the first of a version after a day in which no one opened it, and the first after the demo reset.

**With the cache stopped, a read takes no longer than without one.** The first read after the stop waits for the cache, for 250 ms at the most. From then on no read asks it, and the app asks by itself every ten seconds whether it is back. One after another, the medians of the last column are at or below the first's, but for the largest version in the second run, 102 against 96. No request of either run failed.

**Few reads of these two runs took much longer than the median.** On 2026-10-10, one read in twenty of a seeded version without a cache took over 80 ms where the median was 34, and what such reads wait for is the disk of the machine they were measured on. Every request writes when its session was last used, and PostgreSQL does not say that it is written before the disk has it. On this machine the disk took 5 ms for that at the median and over 50 ms in stretches when it was looked at on 2026-10-11. The cache has no part in it, and the deployed host does not show it: [what was measured](#the-long-reads) is below. In these two runs the longest read of a seeded version without a cache took 19 ms, where the median is 16. The reads that took over twice their median, one after another, are nearly all of the column that finds the cache empty: in the second run 71 ms against 24 for a seeded version and 63 against 26 for a comparison, and in the first 85 against 24 for a comparison. Whether those waited for the disk was not looked at.

**At no size does the cache gain what a person notices.** A seeded version opens in 16 ms without the cache and in 9.6 with it. The largest catalog there can be opens in about 100 ms without it and in 83 with it, where on 2026-10-10 the cache saved nearly half a second for each opening. Eight at once, the largest version takes 160 ms with the cache, against 199 without one and 172 with it stopped. What the cache still spares is PostgreSQL's part: six statements for each read, and twelve for a comparison. What that is worth with many readers on the host's two processors was not measured, here or there. Against it stand the memory it may take on the host, [64 MB](adr/0017-the-cache-runs-on-the-host-within-64-mb.md), and a process more to run. Whether it is still worth that is a decision of its own, and is not taken here: the app is left as it is.

**It does nothing for editing.** A working copy is never in the cache. Saving a cell of the largest catalog answered with its issues after about half a second when the cache was first measured. It has been [answered in a median of 57 ms](largest-catalog-measurements.md) since the cells are read by a mapper written for them, which is no doing of the cache.

## Reads of one version that arrive together

Twenty reads of the largest version were sent at once, with the cache holding nothing of it, and the statements that read its cells were counted.

| | Times PostgreSQL was asked for the cells |
|---|---|
| As first built | 10 |
| With the loads of one version made one at a time | 1 |

The API has ten connections to PostgreSQL, so ten of the twenty read at a time. As first built, each of the ten found the cache empty and read the contents, and the ten after them found them in the cache. Now the reads of one version that the cache does not hold take turns inside one process: the first reads PostgreSQL and gives the cache the contents, and the others wait and then find them there. The API and the worker are two processes, and each takes its own turns.

## The long reads

Reads of the seeded Compact SUV 2026 v2 were sent one after another for 30 seconds, without a cache, on 2026-10-11. One command, run in `e2e/`, starts the stack, sends them, and says how even their times were:

```sh
npm run reads:even
```

```
796 reads of a seeded Approved version in 30 seconds, one after another, without a cache: median 27 ms, 144 over twice the median, and at the most 3 of those one after another.
```

The long reads do not follow one another. One or two short ones come between them, and it is the long ones that grow and then start low again. In the first run below, the long reads of one stretch took 58, 63, 65, 69, 75, 77, 83, and 88 ms, in about a second.

### What they wait for

The reads were sent again with PostgreSQL logging every statement with its time, the API logging every request with its own, and Java logging its garbage collections. That is the first row. For the second, PostgreSQL was also told not to wait for the disk when a transaction ends, with `synchronous_commit = off`, and nothing else was changed.

| | Reads | Median | Over twice the median | Longest |
|---|---|---|---|---|
| PostgreSQL waits for the disk, as it does unless told otherwise | 694 | 27.5 ms | 151 | 193 ms |
| It does not wait | 1,338 | 22.2 ms | 0 | 31 ms |

In the first row, the time is in one place: the `COMMIT` that ends the writing of the session's last use.

| | Reads | The read | The API's own time | That `COMMIT` |
|---|---|---|---|---|
| Reads over twice the median | 151 | 91 ms | 90 ms | 68 ms, and never under 30 |
| The others | 543 | 30 ms | 28 ms | 6.9 ms, with a median of 4.4 |

They are averages. A read without that `COMMIT` took 23 ms at the median and 33 at the most, so no read was long for another reason. Of the 694 commits, 257 took over 10 ms and 100 over 50.

The disk does the same with no app and no PostgreSQL. A script wrote 8 kB to a file of 16 MB in a Docker volume and had it flushed with `fdatasync`, as PostgreSQL does at a commit, then waited 24 ms and did so again, for 30 seconds. Of 598 flushes the median took 9.3 ms and 293 over 10 ms, and they grew as the commits do: 53, 66, 87, 91, 97, 104 ms one after another, and low again a second later. Docker keeps its volumes on btrfs here, with compression, on a disk the rest of the machine uses. On the machine's other disk, which has ext4, the same script's 1,227 flushes took 0.2 ms at the median and 4 ms at the most. Why the first of the two flushes so was not looked into: it is the file system's or the disk's, and not the app's.

### What it is not

- **The cache.** The app had no address for one.
- **Garbage collection.** Java collected 76 times in the 30 seconds, for 3.1 ms at the most and 92 ms in all, where a long read takes 30 ms more than a short one at the least.
- **The API's threads and its connections to PostgreSQL.** The reads were spread evenly over ten threads, and every thread had its share of the long ones, between 12 and 19 of about 70. Two connections carried the statements of all the reads, and both had long commits, of 170 and 130 ms at the most.
- **The session's row.** Every request writes it anew, so old copies of it pile up: 201 were left at the end, and PostgreSQL had cleared them away once. Reading the session took 0.02 ms and writing it 0.04 ms, in long reads and in short ones.
- **Any other statement.** Each of the others took the same in long reads as in short ones, to within 0.05 ms. Reading the cells took 0.21 ms in both. PostgreSQL logged no checkpoint in the 30 seconds.
- **The web server and the client that sent the reads.** A read took 1.6 ms longer than the API's own time for it at the median, and 2.9 ms at the most.
- **The tracing of every request.** It ran in both rows of the first table, and the second has no long read.

### What it costs

On a machine whose disk flushes like this one, about one request in five takes 30 to 170 ms longer than the others. That is true of every request and not only of reads, since each writes its session. One such wait is below what a person notices. The 95th percentiles and the longest times of the tables above were measured on the same disk, so they say more of it than of the app. The medians hold: at the median the commit takes 5 ms.

The app is left as it is. The stretches are this machine's, and the app does not choose the disk it is measured on. That PostgreSQL waits for the disk is what keeps a saved edit when the machine fails, so it is not turned off for the measuring either: the host waits too, and the times here should be of the app as it runs there. The table above says what the waiting costs here: the median is 22 ms without it and 27.5 with it.

### On the deployed host

On 2026-10-11, from 10:26:58 UTC, the demo author's reads of Compact SUV 2026 v2 were sent to the deployed app one after another for 30 seconds, from the machine of the measurements above, and nothing else was sent. The cache held the version throughout.

| | Reads | Median | Over twice the median | Longest |
|---|---|---|---|---|
| As timed where they were sent from, with the way there and back | 391 | 70 ms | 3, never two together | 156 ms |
| The API's own times, from its log | 396 | 16 ms | 22, at the most 3 together | 95 ms |

The API's log has the five reads that were sent first and not timed as well. Of its 22 over twice the median, 8 are among the first 20 reads, which took longer than the rest as a whole and less from one to the next: 47, 31, 53, 30, 32, 35, 25, 38 ms at the start, where three reads in four of the half minute took between 12 and 19 ms. Why the first took longer was not looked at. The other 14 are spread over the half minute, one to three at a time, between 34 and 60 ms but for one of 95, and do not grow from one to the next. So the host does not show the stretches. Its Docker volumes are on xfs. With the cache holding the version a read there still writes its session and waits for the disk, as every request does.

## How they were measured

One command, run in `e2e/`, with nothing else on the stack's ports:

```sh
npm run cache:measure
```

It runs `e2e/cache-workload.mjs`, which does all of this and prints the three tables and the count of the twenty:

1. It starts the browser tests' stack with the largest catalog and without an address for the cache, as `CACHE_HOST= LARGEST_CATALOG=true npm run stack:up` does. The stack serves the production build of the frontend behind the web server, with the API, the worker, PostgreSQL, and Redis each in a container.
2. It signs in as the demo author in a browser, and from then on sends every request with Node's own HTTP client and the session's cookie, to the web server's address.
3. For each of the three kinds of request it sends 5 that are not timed, then 30 one after another, then 8 at once three times. Each is timed from sending until the whole answer has been read. That is the first column.
4. It starts the API and the worker again with the cache's address, signs in again, and does the same twice. The first time it deletes the entries of the versions a request reads before every request, and before every 8 at once, so that each finds the cache empty. The second time it deletes nothing.
5. It deletes the largest version's entry, sends 20 reads of that version at once, and counts the statements that read its cells.
6. It stops the cache with `docker compose stop` and does step 3 once more, which is the last column. A request that is not answered with success stops the script.
7. It removes the stack and its data.

The statements are counted apart from the times, for one more request of each kind in each column. PostgreSQL is told to log every statement, the request is sent, and the log since then is read. Statements that start or end a transaction are left out, and so are those of the two things the API does by the clock: looking for the messages of new jobs, and ending sessions that have run out. With `STATEMENTS=1` the script prints the ones it counts.

The count of ten is from an earlier run, made before the loads of one version took turns. The count of one is from both runs here.

The half minute of reads is apart from the two runs, and is [above](#the-long-reads) with its command. The script that logged every statement and the one that flushed a file were not kept.

## The cache stopped on the host

On 2026-10-11 the cache was stopped on the host on purpose, for two minutes, with `docker compose stop cache`, and started again. Meanwhile the demo author opened Compact SUV 2026 v2 on the deployed app and compared it with v1, four times over. Times are in UTC.

| | |
|---|---|
| 04:53:28 | The cache is stopped. |
| 04:53:35 | The first read after it waits for the cache and is answered from PostgreSQL. The API's log says that the cache stopped answering. |
| 04:53:35 to 04:54:56 | Four openings and four comparisons, each answered 200 and each the same as before the stop: the same contents, the same issues, the same differences. The API is ready each time it is asked. |
| 04:55:37 | The cache is started again. |
| 04:55:45 | The API's log says that the cache answers. Nobody asked for the app's health: the app found the cache by itself, 8 seconds after it was back. |
| 04:56:40 | The first opening after that finds the cache empty and fills it, and the ones after it are answered from the cache. |

The API's log has those two lines about the cache and no other. The alarm on the backend's health stayed as it was, and did not change all that night.

The API's own times for these requests, in milliseconds:

| | Opening the version | Comparing the two |
|---|---|---|
| Before the stop, the cache holding both | 43 | 59 |
| The first after the stop, which waited for the cache | 384 | 165 |
| The other three while it was stopped | 110, 107, 69 | 131, 110, 115 |
| After its return, the cache holding both | 37, 25 | 78, 34 |

They are one person's requests, a handful of each, on the seeded catalogs: enough to say that the app answers as before with the cache stopped, and too few to say what the cache gains there.

The dashboard's chart of the reads, by how the cache answered them:

![Reads of an Approved version's contents a minute, by how the cache answered: fallbacks from 04:13 to 04:15 and at 04:54 and 04:55, each followed by hits](cache-stopped-on-the-host.png)

The reads at 04:00 are the ones sent to [measure the host's memory](host-memory-measurements.md). The fallbacks at 04:54 and 04:55 are this stop, and the hits and the misses at 04:57 the reads after it. A count is sent once a minute, so it shows in the minute after its reads.

The fallbacks from 04:13 to 04:15 are an earlier stop, from 04:12:20 to 04:14:30, on the app as it was built two releases before. Readers saw the same then: every opening and comparison answered 200, the same as before. But the app went on reading from PostgreSQL for half a minute after the cache was back, until its health was asked for. The client had come to wait half a minute between its tries to reach a cache that was gone. It now tries every two seconds, which is what the 8 seconds above show.

## Environment

- The two runs of the tables were measured on 2026-10-12, between 16:22 and 16:27 UTC, with the application as the repository built it that day. The half minutes of reads are of 2026-10-11.
- AMD Ryzen 9 5900X, 32 GB of memory, Linux 7.2. Docker 29.9.0 with Compose 5.6.0 keeps its volumes on btrfs.
- Node 24.21.0 sent the requests. Chromium, driven by Playwright 1.63, signed in.
- Spring Boot 4.1.1 on Java 25, PostgreSQL 18.6, Redis 8.10.2, and Caddy 2.11.6, each in a container on the same machine.
