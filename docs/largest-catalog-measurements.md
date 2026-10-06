# Measurements at the largest catalog size

How the app behaves with the largest catalog there can be: 500 feature rows by 96 offerings (12 trims, each sold in 8 regions). These are measurements under synthetic load, on one machine and on generated data. They say how the app is built, and nothing certain about a deployed one.

Three things limit what they say:

- **Half of the cells are stored.** The catalog measured has a Standard or an Available cell for 24,000 of its 48,000 cells; the rest are Not offered, which is no stored cell. Reading and copying a catalog grow with its stored cells, so a catalog with every cell stored has twice as many to read and to copy.
- **A save does not validate yet.** Saving a cell stores it and records the change. Nothing validates a catalog yet, and validating the whole catalog on every edit, as decided, will add to every save. The save times here are of the write alone.
- **The working copies measured are new.** None had more than 200 entries in its change history.

## Results

Times are in milliseconds. A range is the range over the runs made; the method says how many.

### In the catalog editor

| | Measured |
|---|---|
| Creating a working copy from the Approved version, from the click to the answer | 280 to 603 |
| Opening the editor, until the first rows are on screen | 861 to 947 |
| Reading the catalog on its own, 1.8 million characters of JSON | 480 to 584 |
| Setting a cell, until the page shows the new value | within one frame |
| Setting a cell, until the answer to its save has arrived | median 11 to 22, 95th percentile 87 to 128, longest 88 to 136 |
| The save request on its own, as the page sends it | median 9 to 20, 95th percentile 85 to 126, longest 86 to 134 |
| The same save sent without the page, through the web server | median 9, longest 14 |
| The same save sent without the page, straight to the backend | median 8, longest 9 |

A cell shows its new value before its save is answered, so a slow answer does not hold up typing.

Between 5 and 15 of every 40 saves the page sent took more than 50 ms, and the longest 136 ms. A save sent 40 times by a plain HTTP client with the same session was answered in under 14 ms every time, whether it went through the web server or straight to the backend. So the slow answers were seen only when the page sent the save. What delays them there was not traced.

### Scrolling

The screen refreshes every 16.7 ms. A frame that lasts one refresh is as smooth as it gets, and a pause becomes noticeable at about three. The table counts frames by how many refreshes they lasted, over three passes.

| | 1 refresh (17 ms) | 2 (33 ms) | 3 (50 ms) | 4 (67 ms) | 5 or 6 (83 to 100 ms) |
|---|---|---|---|---|---|
| Down, 1 row a frame, 497 frames | 389 to 392 | 99 to 103 | 2 to 9 | none | none |
| Down, 5 rows a frame, 100 frames | 2 | 21 to 28 | 66 to 73 | 3 to 5 | 1 or 2 |
| Across, 2 offerings a frame, 43 frames | 43 | none | none | none | none |

The editor scrolls down more slowly than the page the matrix was measured on by itself, a development build with a generated catalog and no backend:

| | That page | The editor |
|---|---|---|
| Down, 1 row a frame | no frame longer than 2 refreshes | 2 to 9 frames of 497 lasted 3 |
| Down, 5 rows a frame | median 2 refreshes, longest 3 or 4 | median 3 refreshes in five passes of six, longest 4 to 6 |

The decision record "The matrix is one PrimeNG Table with its own subheaders and cell editing" keeps showing one category at a time open as its other option, should real catalogs prove slower than the generated one. These figures are what there is to judge that by.

### Copying a catalog

A working copy is created by copying the contents of an Approved version. The app does it with one call to a stored function. The same five statements sent one by one from the application were timed for comparison; that variant exists only in the test that times it.

| | Median | Fastest | Slowest |
|---|---|---|---|
| One call to the copy function | 225 to 244 | 223 to 241 | 228 to 250 |
| The same statements, one by one | 225 to 244 | 224 to 242 | 228 to 248 |

Each range is over five runs of nine copies. The two ways take the same time. The function saves four trips to the database, and here, with the database on the same machine as the application, four trips cost too little to be seen beside the copy itself. The comparison says nothing about a database further away, where each trip costs more.

The same test times 60 saves of one cell inside the test's own process, with no server, no session, and no connection: a median of 1.5 to 3.1 ms, and never more than 4.2 ms.

## How they were measured

### The catalog

The largest catalog is made on request and is the same every time: a vehicle line of its own, "Largest Catalog", with 12 trims, 8 regions, and 500 features made for it, and one Approved version in which every trim is sold in every region. In each offering a quarter of the features are Standard and a quarter Available.

- For the stack that was measured, run `LARGEST_CATALOG=true npm run stack:up` in `e2e/`. It serves the production build at `http://localhost:8092`, and `npm run stack:down` removes it with its data.
- For development, run `LARGEST_CATALOG=true docker compose up --build -d` and `npm start` in `frontend/`.
- In a test, `LargestCatalog.create()` makes it.

The backend makes the catalog at startup where `app.largest-catalog` is true, unless it is there already. It is false unless set, and nothing a visitor can reach makes the catalog. Its trims, regions, and features stay in the library, where every catalog can add them; removing them means removing the database's data.

### In the catalog editor

The stack ran on one machine: the production build of the frontend behind the web server, the backend, and the database, the last three each in a container. A browser automation script, not kept in the repository, signed in as the demo author and:

1. created a working copy of the largest catalog in the new catalog dialog, timing it from the click on Create until the answer;
2. reloaded the editor three times, timing each from the reload until the fourth row of the matrix was on screen, and after each reload read the catalog once more by itself to time the read alone;
3. scrolled the matrix from top to bottom one row a frame, then five rows a frame, then from side to side two offerings a frame, and recorded the time between one animation frame and the next;
4. set 40 cells spread over the rows in view by sending each the key a person would type, and timed each from the key press to the next animation frame, and from the key press until the browser reported the end of the answer to the save. "The save request on its own" is the same answer timed from the moment the browser reports having sent the request.

The script ran in full twice, on two starts of the stack, and made three passes of steps 3 and 4 in each run. The ranges are over both runs, so over six reloads and six passes of 40 cells. The scroll table is from the second run, which counted refreshes. The first recorded milliseconds: down five rows a frame, a median of 50 in two of its three passes and 33 in the third, and a longest frame of 67 to 83.

Six creations were timed: 470, 308, and 280 on the first stack, one in each of three runs of the script, and 456, 525, and 603 on the second, one after another in one run.

Last, in the second run, the script saved one cell 40 times, to Standard and to Available in turn, through its own HTTP client with the session's cookies: first at the web server's address and then at the backend container's own, timing each from sending to the answer.

The scrolling is done the same way as on the page at `/dev/matrix`, whose figures for the matrix alone are in [Matrix measurements at the largest catalog size](matrix-measurements.md).

### Copying a catalog, and a save inside the test

`LargestCatalogIT` makes the largest catalog in the test's database and writes two sets of times to the log:

- nine copies made with one call to the copy function and nine made with the function's statements sent one by one, taking turns, after two of each that are not timed. The statements are read from the migration that defines the function. A copy is timed from before its first statement until after its last, inside one transaction, without the transaction's start and commit.
- sixty saves of one cell each of a working copy of the largest catalog, after five that are not timed, each timed around the request as the test hands it to the application.

To repeat it, run `./mvnw verify -Dit.test=LargestCatalogIT` in `backend/` and read the two lines the test logs.

## Environment

- Measured on 2026-10-06.
- AMD Ryzen 9 5900X, 32 GB of memory, Linux 7.2. Docker keeps its volumes on btrfs.
- Chromium 153.0.8010.12, headless, in a 1600 by 900 window, driven by Playwright 1.63.
- Angular 22.2.1 and PrimeNG 22.1.2 in a production build, served by Caddy 2.11.6.
- Spring Boot 4.1.1 on Java 25 and PostgreSQL 18.6, each in a container on the same machine as the browser.
- The copy and the save inside the test ran in the test's own Java process, against PostgreSQL 18.6 in a container the tests start.
