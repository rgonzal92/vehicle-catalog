-- A decision about a catalog that was submitted for review: a reviewer approved it or rejected it,
-- or it was returned to its owner because another catalog of its lineage was approved first. A
-- return is the system's and has no reviewer. A rejection always says why.
CREATE TABLE catalog_review (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    catalog_id  bigint      NOT NULL REFERENCES catalog (id),
    reviewer_id bigint REFERENCES app_user (id),
    decision    text        NOT NULL
        CHECK (decision IN ('APPROVED', 'REJECTED', 'RETURNED_STALE')),
    comment     text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT catalog_review_is_a_reviewers_unless_it_is_a_return
        CHECK ((decision = 'RETURNED_STALE') = (reviewer_id IS NULL))
);

-- The decisions about one catalog are read together, newest first.
CREATE INDEX catalog_review_of_catalog ON catalog_review (catalog_id, id DESC);
