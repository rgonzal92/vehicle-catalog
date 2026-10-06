# Measurements at the largest catalog size

How the app behaves with the largest catalog there can be: 500 feature rows by 96 offerings (12 trims, each sold in 8 regions). These are measurements under synthetic load, on one machine and on generated data. They say how the app is built, and nothing certain about a deployed one.

The catalog measured has a Standard or an Available cell for half of its 48,000 cells, which is 24,000 stored cells; the rest are Not offered.

## Results

Times are in milliseconds. Where a range is given, it is the range over three runs.

### In the catalog editor

| | Measured |
|---|---|
| Creating a working copy from the Approved version, from the click to the answer | 280 to 470 |
| Opening the editor, until the first rows are on screen | 908 to 925 |
| Reading the catalog on its own, 1.8 million characters of JSON | 480 to 508 |
| Scrolling down, 1 row a frame | median 17, 95th percentile 33, longest 33 to 50, no frame of 497 over 50 |
| Scrolling down, 5 rows a frame | median 33 to 50, 95th percentile 50, longest 67 to 83, 6 to 19 frames of 100 over 50 |
| Scrolling across, 2 offerings a frame | 17 for every frame |
| Setting a cell, until the page shows the new value | within one frame, 17 |
| Setting a cell, until the answer to its save has arrived | median 11 to 13, 95th percentile 87 to 128, longest 88 to 136 |
| The save request on its own | median 9 to 11, 95th percentile 85 to 126, longest 86 to 134 |

At 60 frames a second a frame lasts 17 ms; a pause becomes noticeable at about 50 ms.

Most saves are answered in about 10 ms, but in each run between 5 and 11 of the 40 took 85 to 135 ms. The backend on its own, called without a browser and a web server in front of it, answers the same save in about 4 ms and never took more than 6 ms over 60 saves. The slow answers therefore arise between the browser, the web server, and the backend. Where exactly was not traced.

A cell shows its new value before its save is answered, so a slow answer does not hold up typing.

### Copying a catalog

A working copy is created by copying the contents of an Approved version. The app does it with one call to a stored function. The same five statements sent one by one from the application were timed for comparison; that variant exists only in the test that times it.

| | Median | Fastest | Slowest |
|---|---|---|---|
| One call to the copy function | 225 to 233 | 223 to 229 | 228 to 250 |
| The same statements, one by one | 225 to 233 | 224 to 229 | 228 to 246 |

Each range is over two runs of nine copies. There is no difference to speak of. Almost all of the time goes into inserting 24,000 cells and checking each against its feature row and its offering, which is the same work either way. The function saves four trips to the database, and with the database on the same machine a trip costs a fraction of a millisecond. The saving grows with the distance to the database: four times whatever one trip costs.

## How they were measured

### The catalog

The largest catalog is made on request and is the same every time: a vehicle line of its own, "Largest Catalog", with 12 trims, 8 regions, and 500 features made for it, and one Approved version in which every trim is sold in every region. In each offering a quarter of the features are Standard and a quarter Available.

- In the local stack, start it with `LARGEST_CATALOG=true docker compose up --build -d`. The backend then makes the catalog at startup unless it is there. No deployed configuration sets this, and nothing a visitor can reach makes it.
- In a test, `LargestCatalog.create()` makes it.

### In the catalog editor

The full stack ran on one machine: the production build of the frontend behind the web server, the backend, and the database, each of the last three in a container, with the largest catalog made at startup. A browser automation script, not kept in the repository, signed in as the demo author, created a working copy of the largest catalog in the new catalog dialog, and then:

1. reloaded the editor three times, timing each from the reload until the fourth row of the matrix was on screen, and read the catalog once more by itself to time the read alone;
2. scrolled the matrix from top to bottom one row a frame, then five rows a frame, then from side to side two offerings a frame, and recorded the time between one animation frame and the next;
3. set 40 cells spread over the rows in view by sending each the key a person would type, and timed each from the key press to the next animation frame, and from the key press until the browser reported the end of the answer to the save. "The save request on its own" is the same answer timed from the moment the browser sent the request.

Steps 2 and 3 were run three times in one session. The scrolling is the same as on the page at `/dev/matrix`, whose figures for the matrix alone, on generated data and without a backend, are in [Matrix measurements at the largest catalog size](matrix-measurements.md).

### Copying a catalog, and the backend on its own

`LargestCatalogIT` makes the largest catalog in the test database and writes two sets of times to the log:

- nine copies made with one call to the copy function and nine made with the statements sent one by one, taking turns, after two of each that are not timed. A copy is timed from before its first statement until after its last, inside one transaction, without the transaction's start and commit.
- sixty saves of one cell each of a working copy of the largest catalog, after five that are not timed, each timed around the whole request as the test sends it to the application.

To repeat it, run `./mvnw verify -Dit.test=LargestCatalogIT -Dsurefire.skip=true` in `backend/` and read the two lines the test logs.

## Environment

- Measured on 2026-10-06.
- AMD Ryzen 9 5900X, 32 GB of memory, Linux 7.2. Docker keeps its volumes on btrfs.
- Chromium, headless, in a 1600 by 900 window, driven by Playwright 1.63.
- Angular 22.2.1 and PrimeNG 22.1.2 in a production build, served by Caddy 2.11.6.
- Spring Boot 4.1.1 on Java 25 and PostgreSQL 18.6, each in a container on the same machine as the browser.
