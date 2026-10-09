-- The trace of the request that wrote a job, in the form the W3C gives it, which travels with the
-- job's message so that the worker's handling of it is part of the same trace. A job that no
-- request caused has none.
ALTER TABLE outbox ADD COLUMN traceparent text;
