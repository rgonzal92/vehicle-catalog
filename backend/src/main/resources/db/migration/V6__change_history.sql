-- A catalog's change history: one row for every change an edit made, with who made it, when, its
-- kind, and a payload holding the old and new values. A copy of a catalog does not take it along.
CREATE TABLE catalog_change (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    catalog_id bigint      NOT NULL REFERENCES catalog (id),
    actor_id   bigint      NOT NULL REFERENCES app_user (id),
    at         timestamptz NOT NULL DEFAULT now(),
    kind       text        NOT NULL,
    payload    jsonb       NOT NULL
);

-- The history is read one catalog at a time, newest first.
CREATE INDEX catalog_change_catalog_idx ON catalog_change (catalog_id, id DESC);
