-- How many Errors and Warnings validation found in a working copy, and the catalog's revision and
-- the library's that it found them at. A person's list of working copies keeps them when it reads
-- a working copy whole, and lists a working copy from them while both revisions are still those.
-- It goes when its catalog goes, so an image that does not know the table still deletes a
-- working copy.
CREATE TABLE working_copy_count (
    catalog_id       bigint PRIMARY KEY REFERENCES catalog (id) ON DELETE CASCADE,
    revision         bigint      NOT NULL,
    library_revision bigint      NOT NULL,
    error_count      int         NOT NULL,
    warning_count    int         NOT NULL,
    counted_at       timestamptz NOT NULL DEFAULT now()
);
