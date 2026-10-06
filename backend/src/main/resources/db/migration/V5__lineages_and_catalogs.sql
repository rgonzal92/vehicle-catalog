-- A person the seed records before their first login has not signed in yet.
ALTER TABLE app_user ALTER COLUMN last_login_at DROP NOT NULL;

-- One vehicle line in one model year. It holds that line-year's chain of Approved versions and
-- points at the current one.
CREATE TABLE lineage (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_line_id    bigint NOT NULL REFERENCES vehicle_line (id),
    model_year         int    NOT NULL,
    current_catalog_id bigint,
    UNIQUE (vehicle_line_id, model_year)
);

-- A working copy (Draft or Submitted) or an Approved version of a lineage. Approval changes the
-- working copy in place, so both are the same kind of row.
CREATE TABLE catalog (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lineage_id      bigint NOT NULL REFERENCES lineage (id),
    name            text   NOT NULL,
    owner_id        bigint NOT NULL REFERENCES app_user (id),
    status          text   NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED')),
    -- The Approved version this catalog was copied from, if any.
    base_catalog_id bigint REFERENCES catalog (id),
    -- Increases by one on every change to the catalog.
    revision        bigint NOT NULL DEFAULT 0,
    version_number  int,
    submit_note     text,
    submitted_at    timestamptz,
    approved_by     bigint REFERENCES app_user (id),
    approved_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (lineage_id, version_number),
    -- A catalog has a version number exactly when it is Approved.
    CONSTRAINT catalog_approved_has_version
        CHECK ((status = 'APPROVED') = (version_number IS NOT NULL))
);

-- One owner cannot have two working copies whose names differ only by case.
CREATE UNIQUE INDEX catalog_working_copy_name_key ON catalog (owner_id, lower(name))
    WHERE status <> 'APPROVED';

ALTER TABLE lineage
    ADD CONSTRAINT lineage_current_catalog_fkey
        FOREIGN KEY (current_catalog_id) REFERENCES catalog (id);

-- A catalog's contents are keyed by the library's own identities, so a copy needs no remapping.
-- The approved_* columns hold the labels an Approved version had when it was approved; a working
-- copy leaves them empty and shows the library's current labels.

CREATE TABLE catalog_trim (
    catalog_id          bigint NOT NULL REFERENCES catalog (id),
    trim_id             bigint NOT NULL REFERENCES trim (id),
    approved_name       text,
    approved_sort_order int,
    PRIMARY KEY (catalog_id, trim_id)
);

CREATE TABLE catalog_region (
    catalog_id    bigint NOT NULL REFERENCES catalog (id),
    region_code   text   NOT NULL REFERENCES region (code),
    approved_name text,
    PRIMARY KEY (catalog_id, region_code)
);

-- An offering: one trim sold in one region.
CREATE TABLE catalog_trim_region (
    catalog_id  bigint NOT NULL,
    trim_id     bigint NOT NULL,
    region_code text   NOT NULL,
    PRIMARY KEY (catalog_id, trim_id, region_code),
    FOREIGN KEY (catalog_id, trim_id) REFERENCES catalog_trim ON DELETE CASCADE,
    FOREIGN KEY (catalog_id, region_code) REFERENCES catalog_region ON DELETE CASCADE
);

-- A feature row.
CREATE TABLE catalog_feature (
    catalog_id             bigint NOT NULL REFERENCES catalog (id),
    feature_id             bigint NOT NULL REFERENCES feature (id),
    approved_name          text,
    approved_category_code text REFERENCES category (code),
    PRIMARY KEY (catalog_id, feature_id)
);

-- Cells are sparse: a row exists only for Standard or Available, and no row means Not offered. The
-- composite keys keep a cell on a feature row and an offering of its own catalog.
CREATE TABLE catalog_cell (
    catalog_id   bigint NOT NULL,
    feature_id   bigint NOT NULL,
    trim_id      bigint NOT NULL,
    region_code  text   NOT NULL,
    availability text   NOT NULL CHECK (availability IN ('S', 'A')),
    PRIMARY KEY (catalog_id, feature_id, trim_id, region_code),
    FOREIGN KEY (catalog_id, feature_id) REFERENCES catalog_feature ON DELETE CASCADE,
    FOREIGN KEY (catalog_id, trim_id, region_code) REFERENCES catalog_trim_region ON DELETE CASCADE
);
