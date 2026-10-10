-- A note an admin uploads about one vehicle line's model year. Its file is kept in a bucket, under
-- the document's id. A document waits until the worker has read it, and is then ready to be
-- searched, or has failed with the reason.
CREATE TABLE document (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title           text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 80),
    vehicle_line_id bigint      NOT NULL REFERENCES vehicle_line (id),
    model_year      int         NOT NULL,
    file_name       text        NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('MD', 'TXT', 'PDF')),
    size_bytes      int         NOT NULL,
    uploaded_by     bigint      NOT NULL REFERENCES app_user (id),
    uploaded_at     timestamptz NOT NULL DEFAULT now(),
    status          text        NOT NULL DEFAULT 'WAITING'
        CHECK (status IN ('WAITING', 'RUNNING', 'READY', 'FAILED')),
    -- Why it failed, for one that did.
    reason          text
);
