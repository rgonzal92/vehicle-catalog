-- What asking the language model has cost, and what it may yet cost: a row for each request, with
-- the most it could cost, reserved before the model is asked, and what it did cost, once that is
-- known. A day is a UTC calendar day. A request of the worker's own belongs to no person. The demo
-- reset leaves this table alone: it refers to no table that a reset empties.
CREATE TABLE ai_spend (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    day        date NOT NULL,
    user_id    bigint REFERENCES app_user (id),
    purpose    text NOT NULL,
    reserved   numeric(12, 8) NOT NULL CHECK (reserved >= 0),
    spent      numeric(12, 8) CHECK (spent >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ai_spend_by_day ON ai_spend (day, user_id);
