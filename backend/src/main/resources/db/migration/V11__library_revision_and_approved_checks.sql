-- The library's revision: one number that goes up whenever the library changes in a way that can
-- break a catalog. The table holds one row and can hold no other.
CREATE TABLE library_state (
    the_only_row boolean PRIMARY KEY DEFAULT true CHECK (the_only_row),
    revision     bigint  NOT NULL DEFAULT 0
);

INSERT INTO library_state DEFAULT VALUES;

-- What validation last found in an Approved version, and which revision of the library it was
-- checked against. The dashboard reads it, so that it need not validate every catalog it lists.
CREATE TABLE approved_check (
    catalog_id       bigint PRIMARY KEY REFERENCES catalog (id),
    library_revision bigint      NOT NULL,
    status           text        NOT NULL CHECK (status IN ('OK', 'NEEDS_REVISION')),
    error_count      int         NOT NULL,
    checked_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT approved_check_needs_revision_for_its_errors
        CHECK ((status = 'NEEDS_REVISION') = (error_count > 0))
);
