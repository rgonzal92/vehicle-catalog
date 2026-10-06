# The matrix is one PrimeNG Table with its own subheaders and cell editing

The matrix is a single PrimeNG Table that uses the table's virtual scroll, frozen columns, and multi-level header, which work together at the largest size a catalog can reach: 500 feature rows by 96 offerings. Category subheaders are ordinary rows of the table's data, and cells are plain: the matrix handles the keys itself and puts one dropdown in the cell being edited. That keeps a whole catalog on one scrolling page.

## Considered Options

- **PrimeNG's row grouping for the subheaders.** Not possible: PrimeNG does not draw row-group headers while virtual scroll is on.
- **PrimeNG's cell editing.** It puts a directive and a component in every cell. With 96 offerings, each newly drawn batch of rows took about half a second.
- **One category at a time, without virtual scroll.** Works with both of those PrimeNG features, but a person can no longer scroll through a whole catalog. That option is still open if real catalogs prove slower than the generated one.

## Consequences

- A category subheader scrolls away with its rows. It does not stay pinned under the header.
- Typing S, A, or -, moving between cells with the arrow keys, and opening and closing the dropdown are the matrix's own code, covered by its own tests.
- The matrix keeps four rows drawn beyond each edge of the view, not PrimeNG's default of half the rows in view. Smaller batches shorten the longest frames, from 100 to 117 ms down to 50 to 67 ms at five rows a frame, though some frames still pass 50 ms at that speed, and a very fast scroll can show an empty row for a frame.
- The measurements behind this are in `docs/matrix-measurements.md`.
