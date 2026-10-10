-- What the language model wrote of the changes a catalog was submitted with, for its reviewer to
-- read beside them: one summary for each revision a catalog was submitted at. It is pending until
-- the worker has asked the model, and unavailable when there is none to show, with the reason. It
-- goes when its catalog goes.
CREATE TABLE submission_summary (
    catalog_id bigint NOT NULL REFERENCES catalog (id) ON DELETE CASCADE,
    revision   bigint NOT NULL,
    status     text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'READY', 'UNAVAILABLE')),
    headline   text,
    bullets    jsonb,
    reason     text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (catalog_id, revision)
);
