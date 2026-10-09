-- A rule of the library, which applies to every catalog: a relationship between a source feature
-- and its targets. Its kind never changes. An exclusion is kept as two rules, one for each
-- direction, that share a pair key.
CREATE TABLE global_rule (
    id                bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind              text    NOT NULL
        CHECK (kind IN ('REQUIRES', 'REQUIRES_ONE_OF', 'INCLUDES', 'EXCLUDES')),
    source_feature_id bigint  NOT NULL REFERENCES feature (id),
    -- Whether the rule applies in every region, or only in the ones global_rule_region lists.
    all_regions       boolean NOT NULL,
    pair_key          uuid,
    CONSTRAINT global_rule_pair_key_marks_an_exclusion
        CHECK ((kind = 'EXCLUDES') = (pair_key IS NOT NULL))
);

CREATE TABLE global_rule_target (
    global_rule_id bigint NOT NULL REFERENCES global_rule (id) ON DELETE CASCADE,
    feature_id     bigint NOT NULL REFERENCES feature (id),
    PRIMARY KEY (global_rule_id, feature_id)
);

-- Finds the rules that name a feature as a target, which retiring the feature asks for.
CREATE INDEX global_rule_target_feature ON global_rule_target (feature_id);

CREATE TABLE global_rule_region (
    global_rule_id bigint NOT NULL REFERENCES global_rule (id) ON DELETE CASCADE,
    region_code    text   NOT NULL REFERENCES region (code),
    PRIMARY KEY (global_rule_id, region_code)
);
