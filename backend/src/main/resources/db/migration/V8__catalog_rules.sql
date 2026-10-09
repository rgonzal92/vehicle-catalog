-- A rule that belongs to one catalog: a relationship between a source feature and its targets, in
-- the offerings its trim scope and its region scope cover. Its key is made once and stays with the
-- rule through every copy. Its kind never changes. An exclusion is kept as two rules, one for each
-- direction, that share a pair key.
--
-- The composite keys keep a rule on feature rows, trims, and regions of its own catalog. A trim or
-- a region that leaves the catalog leaves the rule's scope with it.
CREATE TABLE catalog_rule (
    catalog_id        bigint  NOT NULL REFERENCES catalog (id),
    rule_key          uuid    NOT NULL,
    kind              text    NOT NULL
        CHECK (kind IN ('REQUIRES', 'REQUIRES_ONE_OF', 'INCLUDES', 'EXCLUDES')),
    source_feature_id bigint  NOT NULL,
    -- Whether the rule covers every trim, or only the ones catalog_rule_trim lists.
    all_trims         boolean NOT NULL,
    -- Whether the rule covers every region, or only the ones catalog_rule_region lists.
    all_regions       boolean NOT NULL,
    pair_key          uuid,
    PRIMARY KEY (catalog_id, rule_key),
    FOREIGN KEY (catalog_id, source_feature_id) REFERENCES catalog_feature,
    CONSTRAINT catalog_rule_pair_key_marks_an_exclusion
        CHECK ((kind = 'EXCLUDES') = (pair_key IS NOT NULL))
);

CREATE TABLE catalog_rule_target (
    catalog_id bigint NOT NULL,
    rule_key   uuid   NOT NULL,
    feature_id bigint NOT NULL,
    PRIMARY KEY (catalog_id, rule_key, feature_id),
    FOREIGN KEY (catalog_id, rule_key) REFERENCES catalog_rule ON DELETE CASCADE,
    FOREIGN KEY (catalog_id, feature_id) REFERENCES catalog_feature
);

CREATE TABLE catalog_rule_trim (
    catalog_id bigint NOT NULL,
    rule_key   uuid   NOT NULL,
    trim_id    bigint NOT NULL,
    PRIMARY KEY (catalog_id, rule_key, trim_id),
    FOREIGN KEY (catalog_id, rule_key) REFERENCES catalog_rule ON DELETE CASCADE,
    FOREIGN KEY (catalog_id, trim_id) REFERENCES catalog_trim ON DELETE CASCADE
);

CREATE TABLE catalog_rule_region (
    catalog_id  bigint NOT NULL,
    rule_key    uuid   NOT NULL,
    region_code text   NOT NULL,
    PRIMARY KEY (catalog_id, rule_key, region_code),
    FOREIGN KEY (catalog_id, rule_key) REFERENCES catalog_rule ON DELETE CASCADE,
    FOREIGN KEY (catalog_id, region_code) REFERENCES catalog_region ON DELETE CASCADE
);
