# The matrix is one PrimeNG Table with its own subheaders and cell editing

The matrix is a single PrimeNG Table that uses the table's virtual scroll, frozen columns, and multi-level header. It was proven at the largest size a catalog can reach, 500 feature rows by 96 offerings, with all three working together. Two more of the table's features were meant to be part of it and are not used. PrimeNG does not draw row-group headers while virtual scroll is on, so each category subheader is an ordinary row of the table's data. And PrimeNG's cell editing puts a directive and a component in every cell, which at 96 columns made each newly drawn batch of rows take about half a second; the cells are plain instead, and the matrix handles the keys itself and puts one dropdown in the cell being edited. We chose this over the fallback, one category at a time without virtual scroll, because it keeps a whole catalog on one scrolling page.

## Consequences

- A category subheader scrolls away with its rows. It does not stay pinned under the header.
- Typing S, A, or -, moving between cells with the arrow keys, and opening and closing the dropdown are the matrix's own code, covered by its own tests.
- The table keeps four rows drawn beyond each edge of the view, not PrimeNG's default of half the rows in view. Smaller batches keep every frame short; a very fast scroll can show an empty row for a frame.
- The measurements behind this are in `docs/matrix-measurements.md`. If real catalogs prove slower than the generated one, the fallback is still open.
