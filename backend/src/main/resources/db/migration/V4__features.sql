-- Anything a vehicle can be equipped with, defined once and shared by every catalog. Its code never
-- changes. Never deleted, only retired.
CREATE TABLE feature (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          text   NOT NULL UNIQUE,
    name          text   NOT NULL,
    description   text   NOT NULL DEFAULT '',
    category_code text   NOT NULL REFERENCES category (code),
    kind          text   NOT NULL CHECK (kind IN ('FEATURE', 'PACKAGE')),
    status        text   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    -- Counts the changes made to the feature, so a change made from an outdated copy is refused.
    version       bigint NOT NULL DEFAULT 0,
    -- A feature is a package exactly when its category is Packages.
    CONSTRAINT feature_kind_matches_category
        CHECK ((kind = 'PACKAGE') = (category_code = 'PACKAGES'))
);
