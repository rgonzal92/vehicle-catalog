# What the cache gains

How long reads of Approved versions take with the cache and without it, and what PostgreSQL is sent for them. [The cache keeps the contents of Approved versions](adr/0016-the-contents-of-approved-versions-are-read-from-a-cache-that-is-never-needed.md): everything else a read answers with is read from PostgreSQL every time. These are measurements under a made-up load, on one machine and on generated data. They say what the cache saves in the app as it is built, and nothing certain about the deployed one: the deployed host has not been measured.

Four things limit what they say:

- **Everything ran on one machine.** The program that sent the requests, the web server, the API, PostgreSQL, and Redis shared a processor with twelve cores and had no network between them. The host has two processors, which it may use in full only for a while, and runs the worker and an agent beside them.
- **One person read, and nobody wrote.** Every request was the same person's, and nothing else used the app. The API had answered the same requests before any was timed.
- **The data is generated or seeded.** The largest catalog there can be is made by the app for measuring, and the demo has no catalog near its size. The seeded catalog measured has 151 feature rows and 7 offerings.
- **They are two runs.** The tables are of the second, and the medians of both are below them.

## Results

Times are in milliseconds, from sending a request until its whole answer had arrived.

The four columns are the app without an address for the cache; with the cache, when it held nothing of what the request reads; with the cache holding it; and with the cache stopped while the requests were sent.

### One after another, 30 requests

| | Cache off | On and empty | On and holding it | Cache stopped |
|---|---|---|---|---|
| Opening the largest Approved version | median 539, 95th percentile 566, longest 592 | median 556, 95th percentile 574, longest 604 | median 67, 95th percentile 142, longest 175 | median 568, 95th percentile 704, longest 838 |
| Opening a seeded Approved version | median 30, 95th percentile 46, longest 48 | median 34, 95th percentile 106, longest 112 | median 6.4, 95th percentile 7.4, longest 7.7 | median 27, 95th percentile 128, longest 129 |
| Comparing two versions of one lineage | median 44, 95th percentile 68, longest 76 | median 50, 95th percentile 84, longest 167 | median 8.2, 95th percentile 8.8, longest 11 | median 45, 95th percentile 146, longest 293 |

### 8 at once, 3 times

| | Cache off | On and empty | On and holding it | Cache stopped |
|---|---|---|---|---|
| Opening the largest Approved version | median 696, 95th percentile 761, longest 775 | median 630, 95th percentile 697, longest 698 | median 219, 95th percentile 275, longest 283 | median 682, 95th percentile 933, longest 943 |
| Opening a seeded Approved version | median 40, 95th percentile 58, longest 60 | median 37, 95th percentile 45, longest 45 | median 14, 95th percentile 20, longest 22 | median 133, 95th percentile 149, longest 151 |
| Comparing two versions of one lineage | median 56, 95th percentile 64, longest 65 | median 55, 95th percentile 61, longest 63 | median 16, 95th percentile 22, longest 23 | median 55, 95th percentile 137, longest 138 |

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
| The largest version, one after another | 541, 539 | 568, 556 | 67, 67 | 613, 568 |
| A seeded version, one after another | 30, 30 | 33, 34 | 6.9, 6.4 | 36, 27 |
| A comparison, one after another | 44, 44 | 50, 50 | 8.7, 8.2 | 46, 45 |
| The largest version, 8 at once | 709, 696 | 748, 630 | 123, 219 | 700, 682 |
| A seeded version, 8 at once | 34, 40 | 40, 37 | 13, 14 | 38, 133 |
| A comparison, 8 at once | 57, 56 | 63, 55 | 16, 16 | 94, 55 |

One after another, the two runs agree to within a tenth, but for the seeded version with the cache stopped. Eight at once they do not: with the cache holding it, the largest version took 123 in one run and 219 in the other, and with the cache stopped a median moves by where in the run a read asked the cache again. So what eight at once say is how the columns stand to each other, and not the figures. The first run was made before the loads of one version took turns, as below: its eight at once on an empty cache each read PostgreSQL. The statements were the same in both runs.

## What they say

**The cache saves the reading of the contents, which is most of a read.** With the cache holding it, the largest version opens in about an eighth of the time, 67 ms against 539, a seeded one in about a fifth, and a comparison in under a fifth. PostgreSQL is sent 8 statements where it was sent 14: the six that read the contents are not sent. A comparison reads two versions, and is sent 8 where it was sent 20.

**What is left is not the cache's to save.** The 67 ms that remain for the largest version go to finding the session and the person, to what describes the catalog, to validating it against the library of today, and to writing 1.8 million characters of JSON. They were not taken apart.

**A read that finds the cache empty costs a little more than one without a cache.** It reads PostgreSQL as before and then gives the cache what it read, which for the largest version is 2 MB: a median of 556 against 539. The job that follows an approval gives the cache a new version before anyone opens it, so the reads that pay this are the first of a version after a day in which no one opened it, and the first after the demo reset.

**With the cache stopped, reads take what they take without one, and now and then up to a quarter of a second more.** A read that asks the stopped cache waits for it for 250 ms at the most, and for ten seconds after that none asks. One after another, the longest times of the last column are 80 to 250 ms above those of the first, which is that wait. No request of either run failed.

**For the catalogs the demo has, the cache gains nothing that matters.** A seeded version opens in 30 ms without the cache and in 6 with it, and no one notices the 24 between them. What the cache is worth shows at sizes the demo does not have: at the largest size, nearly half a second for each opening.

**It does nothing for editing.** A working copy is never in the cache. Saving a cell of the largest catalog answers with its issues after about half a second, [as measured before](largest-catalog-measurements.md), and does so still.

## Reads of one version that arrive together

Twenty reads of the largest version were sent at once, with the cache holding nothing of it, and the statements that read its cells were counted.

| | Times PostgreSQL was asked for the cells |
|---|---|
| As first built | 10 |
| With the loads of one version made one at a time | 1 |

The API has ten connections to PostgreSQL, so ten of the twenty read at a time. As first built, each of the ten found the cache empty and read the contents, and the ten after them found them in the cache. Now the reads of one version that the cache does not hold take turns inside one process: the first reads PostgreSQL and gives the cache the contents, and the others wait and then find them there. The API and the worker are two processes, and each takes its own turns.

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

The count of ten is from the first run, made before the loads of one version took turns. The count of one is from the second.

## Environment

- Measured on 2026-10-10, with the application as the repository built it that day.
- AMD Ryzen 9 5900X, 32 GB of memory, Linux 7.2. Docker 29.9.0 with Compose 5.6.0 keeps its volumes on btrfs.
- Node 24.21.0 sent the requests. Chromium, driven by Playwright 1.63, signed in.
- Spring Boot 4.1.1 on Java 25, PostgreSQL 18.6, Redis 8.10.2, and Caddy 2.11.6, each in a container on the same machine.
