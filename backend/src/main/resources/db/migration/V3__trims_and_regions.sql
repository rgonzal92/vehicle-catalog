-- An equipment level, defined once and shared by every catalog. Never deleted, only deactivated.
CREATE TABLE trim (
    id         bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text    NOT NULL,
    sort_order int     NOT NULL,
    active     boolean NOT NULL DEFAULT true
);

-- Two trims cannot differ only by the case of their names.
CREATE UNIQUE INDEX trim_name_key ON trim (lower(name));

-- A market, defined once and shared by every catalog. Its code is its identity. Never deleted,
-- only deactivated.
CREATE TABLE region (
    code       text    PRIMARY KEY,
    name       text    NOT NULL UNIQUE,
    sort_order int     NOT NULL,
    active     boolean NOT NULL DEFAULT true
);
