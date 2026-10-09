-- Copies one catalog's contents into another that has none: its trims, regions, offerings, feature
-- rows, cells, and rules, one statement per table. A rule keeps its key, and a pair its pair key. The contents are keyed by the library's identities, so
-- nothing is remapped. The labels frozen at approval stay behind; the copy shows the library's
-- current ones until it is approved itself.
CREATE OR REPLACE FUNCTION copy_catalog(source_id bigint, target_id bigint) RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO catalog_trim (catalog_id, trim_id)
    SELECT target_id, trim_id FROM catalog_trim WHERE catalog_id = source_id;

    INSERT INTO catalog_region (catalog_id, region_code)
    SELECT target_id, region_code FROM catalog_region WHERE catalog_id = source_id;

    INSERT INTO catalog_trim_region (catalog_id, trim_id, region_code)
    SELECT target_id, trim_id, region_code FROM catalog_trim_region WHERE catalog_id = source_id;

    INSERT INTO catalog_feature (catalog_id, feature_id)
    SELECT target_id, feature_id FROM catalog_feature WHERE catalog_id = source_id;

    INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
    SELECT target_id, feature_id, trim_id, region_code, availability
    FROM catalog_cell
    WHERE catalog_id = source_id;

    INSERT INTO catalog_rule (catalog_id, rule_key, kind, source_feature_id, all_trims, all_regions,
                              pair_key)
    SELECT target_id, rule_key, kind, source_feature_id, all_trims, all_regions, pair_key
    FROM catalog_rule
    WHERE catalog_id = source_id;

    INSERT INTO catalog_rule_target (catalog_id, rule_key, feature_id)
    SELECT target_id, rule_key, feature_id FROM catalog_rule_target WHERE catalog_id = source_id;

    INSERT INTO catalog_rule_trim (catalog_id, rule_key, trim_id)
    SELECT target_id, rule_key, trim_id FROM catalog_rule_trim WHERE catalog_id = source_id;

    INSERT INTO catalog_rule_region (catalog_id, rule_key, region_code)
    SELECT target_id, rule_key, region_code FROM catalog_rule_region WHERE catalog_id = source_id;
END
$$;
