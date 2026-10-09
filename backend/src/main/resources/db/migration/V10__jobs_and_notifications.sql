-- Work that follows a change and is done by the worker. A job is written in the transaction of the
-- change that causes it, together with the message that tells the worker of it.

CREATE TABLE job (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'SUCCEEDED', 'FAILED')),
    -- Two changes that call for the same work write one job.
    dedupe_key text        NOT NULL UNIQUE,
    -- What the job is about, such as the catalog that was approved.
    subject    jsonb       NOT NULL,
    attempts   int         NOT NULL DEFAULT 0,
    error      text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- A message for the queue. It is unsent until the queue has taken it.
CREATE TABLE outbox (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id     bigint      NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    payload    jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at    timestamptz
);

CREATE INDEX outbox_unsent ON outbox (id) WHERE sent_at IS NULL;

-- Something a person is told in the app. The event key is what makes telling it twice tell it once.
CREATE TABLE notification (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint      NOT NULL REFERENCES app_user (id),
    kind       text        NOT NULL,
    payload    jsonb       NOT NULL,
    event_key  text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at    timestamptz
);

CREATE INDEX notification_of_user ON notification (user_id, id DESC);
