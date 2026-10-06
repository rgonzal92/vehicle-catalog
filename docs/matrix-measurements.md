# Matrix measurements at the largest catalog size

How the matrix scrolls and edits at 500 feature rows by 96 offerings (12 trims sold in 8 regions). These are measurements under synthetic load, on generated data, taken to decide how the matrix is built. They say nothing certain about a real catalog.

## Results

Times are in milliseconds, and each is the range over three runs. At 60 frames a second a frame lasts 17 ms; a pause becomes noticeable at about 50 ms.

| | As built: plain cells, four rows kept beyond the view | Plain cells, PrimeNG's default of half the rows in view | PrimeNG cell editing in every cell, default rows |
|---|---|---|---|
| Scrolling down, 1 row a frame | median 17, 95th percentile 33, longest 33, no frame of 497 over 50 | median 17, 95th percentile 67, longest 67 to 100, 39 frames of 497 over 50 | not measured |
| Scrolling down, 5 rows a frame | median 33, 95th percentile 50, longest 50 to 67, 8 to 11 frames of 100 over 50 | median 17, 95th percentile 83, longest 100 to 117, 31 or 32 frames of 100 over 50 | median 33, 95th percentile 500 to 533, longest 583 |
| Scrolling across, 2 offerings a frame | 17 for every frame | 17 for every frame | median 17, 95th percentile 33 |
| Setting a cell | median 6, 95th percentile 6, longest 7 | median 8, 95th percentile 8 or 9, longest 9 to 11 | median 22 to 24, 95th percentile 24 to 26, longest 26 to 62 |
| First rows on screen after opening the page | about 0.5 s | about 0.5 s | about 1.1 s |

The first column of figures is the matrix as it is built. The other two are variants it was chosen over, which are not in the repository; see the decision record "The matrix is one PrimeNG Table with its own subheaders and cell editing".

## How they were measured

The page at `/dev/matrix` shows the matrix on a generated catalog of the largest size. It exists only in development builds. Its Measure button:

1. scrolls the matrix from top to bottom one row a frame, then five rows a frame, then from side to side two offerings a frame, and records the time between one animation frame and the next;
2. sets 100 cells spread over the rows in view by sending each the key a person would type, and times each from the key press until Angular has applied the change and the browser has laid the page out again. Painting is not included.

To repeat it, run `npm start` in `frontend/`, open `http://localhost:4200/dev/matrix`, and press Measure. The figures appear on the page.

"First rows on screen" is not part of the button's run. It is the time a browser automation script waited between opening the page and finding the first rows in the document.

## Accessibility

The same page was checked with axe-core 4.13.0, through `@axe-core/playwright`, in three states: editable, editable with a cell's dropdown open, and read-only. It found no violations in any of them. No browser test repeats this check, because the browser tests run against the production build, which does not have the page. The first screen that shows the matrix brings its own check.

## Environment

- Measured on 2026-10-05.
- AMD Ryzen 9 5900X, 32 GB of memory, Linux 7.2.
- Chromium 153, headless, in a 1600 by 900 window. A Playwright 1.63 script, not kept in the repository, opened the page, pressed the button, and read the figures.
- Angular 22.2.1 and PrimeNG 22.1.2, in the development build that `ng serve` makes, which runs slower than a production build.
