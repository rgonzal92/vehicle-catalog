package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the owner of a working copy in status Draft keeps rules that belong to it, and that
 * its rules stay whole when a feature row, a trim, or a region is removed. Each test starts from
 * the seeded catalogs and a working copy of Compact SUV 2026 that Ana owns, with the trims Base,
 * Sport, Touring, and Off-Road, the regions North America and Europe, and its rules taken away.
 */
class CatalogRulesIT extends WorkingCopyTests {
  @Autowired CatalogRules catalogRules;

  /** Ana's working copy, at revision 0. */
  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    // The copy starts with the rules of its base, which these tests do without.
    jdbc.sql("DELETE FROM catalog_rule WHERE catalog_id = :copy").param("copy", copy).update();
  }

  @AfterEach
  void everyExclusionIsAWholePair() {
    assertThat(catalogRules.brokenPairs()).isEmpty();
  }

  @Test
  void theOwnerAddsChangesAndDeletesARuleOfEachKind() {
    var added =
        Stream.of(
                rule("REQUIRES", "SEAT_LEATHER", List.of("Sport"), List.of("EU"), "AUDIO_PREMIUM"),
                rule(
                    "REQUIRES_ONE_OF",
                    "ROOF_PANORAMIC",
                    null,
                    null,
                    "SEAT_LEATHER",
                    "AUDIO_PREMIUM"),
                rule("INCLUDES", "PACKAGE_TOW", null, List.of("NA"), "SEAT_HEATED_FRONT"),
                rule("EXCLUDES", "TRANS_MANUAL", List.of("Base", "Sport"), null, "SEAT_LEATHER"))
            .map(given -> add(ana(), copy, "\"" + revision(copy) + "\"", given))
            .toList();

    assertThat(added).allSatisfy(answer -> assertThat(answer).hasStatusOk());
    assertThat(added.getLast()).headers().hasValue("ETag", "\"4\"");
    assertThat(added.getLast()).bodyJson().extractingPath("$.issues").isNotNull();
    assertThat(sentences(copy))
        .containsExactlyInAnyOrder(
            "Leather Seats requires Premium Audio (on Sport; in Europe)",
            "Panoramic Roof requires one of Premium Audio, Leather Seats",
            "Tow Package includes Heated Front Seats (in North America)",
            "Manual Transmission excludes Leather Seats (on Base, Sport)",
            "Leather Seats excludes Manual Transmission (on Base, Sport)");
    List<Map<String, Object>> oneOf =
        read(open(ana(), copy), "$.snapshot.rules[?(@.kind == 'REQUIRES_ONE_OF')]");
    assertThat(oneOf.getFirst())
        .as("a rule as the catalog shows it")
        .containsEntry("origin", "CATALOG")
        .containsEntry("kind", "REQUIRES_ONE_OF")
        .containsEntry("sourceFeatureId", (int) feature("ROOF_PANORAMIC"))
        .containsEntry("allTrims", true)
        .containsEntry("allRegions", true)
        .containsEntry("pairKey", null);

    var key = keyOf("SEAT_LEATHER", "REQUIRES");
    var changed =
        change(
            ana(),
            copy,
            "\"4\"",
            key,
            rule("REQUIRES", "SEAT_LEATHER", null, List.of("EU", "NA"), "SEAT_HEATED_FRONT"));

    assertThat(changed).hasStatusOk().bodyJson().extractingPath("$.revision").isEqualTo(5);
    assertThat(sentences(copy))
        .contains("Leather Seats requires Heated Front Seats (in North America, Europe)");
    assertThat(keyOf("SEAT_LEATHER", "REQUIRES")).as("a rule keeps its key").isEqualTo(key);
    assertThat(
            change(
                ana(),
                copy,
                "\"5\"",
                key,
                rule("REQUIRES", "SEAT_LEATHER", null, List.of("NA", "EU"), "SEAT_HEATED_FRONT")))
        .as("a change that asks for what is already so")
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.revision")
        .isEqualTo(5);

    assertThat(delete(ana(), copy, "\"5\"", key)).hasStatusOk();
    assertThat(sentences(copy))
        .hasSize(4)
        .doesNotContain("Leather Seats requires Heated Front Seats (in North America, Europe)");
    assertThat(delete(ana(), copy, "\"6\"", key)).as("a rule that is gone").hasStatus(404);

    var history = mvc.get().uri("/api/catalogs/{id}/changes", copy).with(ana()).exchange();
    assertThat(ApplicationIT.<List<String>>read(history, "$.items[*].kind"))
        .as("newest first, an exclusion once")
        .containsExactly(
            "RULE_REMOVED", "RULE_UPDATED", "RULE_ADDED", "RULE_ADDED", "RULE_ADDED", "RULE_ADDED");
    assertThat(ApplicationIT.<String>read(history, "$.items[1].oldValue"))
        .isEqualTo("Leather Seats requires Premium Audio (on Sport; in Europe)");
    assertThat(ApplicationIT.<String>read(history, "$.items[1].newValue"))
        .isEqualTo("Leather Seats requires Heated Front Seats (in North America, Europe)");
  }

  @Test
  void anExclusionIsMadeChangedAndDeletedAsAPair() {
    var made =
        add(
            ana(),
            copy,
            "\"0\"",
            rule(
                "EXCLUDES",
                "TRANS_MANUAL",
                List.of("Sport"),
                List.of("EU"),
                "SEAT_LEATHER",
                "ROOF_PANORAMIC"));

    assertThat(made).hasStatusOk().bodyJson().extractingPath("$.revision").isEqualTo(1);
    assertThat(sentences(copy))
        .as("one pair for each target")
        .containsExactlyInAnyOrder(
            "Manual Transmission excludes Leather Seats (on Sport; in Europe)",
            "Leather Seats excludes Manual Transmission (on Sport; in Europe)",
            "Manual Transmission excludes Panoramic Roof (on Sport; in Europe)",
            "Panoramic Roof excludes Manual Transmission (on Sport; in Europe)");
    assertThat(catalogRules.brokenPairs()).isEmpty();
    assertThat(
            add(
                ana(),
                copy,
                "\"1\"",
                rule("EXCLUDES", "SEAT_LEATHER", List.of("Sport"), List.of("EU"), "TRANS_MANUAL")))
        .as("the same two features the other way round")
        .hasStatus(422);

    var mirrored = keyOf("SEAT_LEATHER", "EXCLUDES");
    assertThat(
            change(
                ana(),
                copy,
                "\"1\"",
                mirrored,
                rule("EXCLUDES", "SEAT_LEATHER", null, null, "AUDIO_PREMIUM")))
        .hasStatusOk();
    assertThat(sentences(copy))
        .as("its pair takes the mirrored change")
        .contains("Leather Seats excludes Premium Audio", "Premium Audio excludes Leather Seats")
        .hasSize(4);
    assertThat(catalogRules.brokenPairs()).isEmpty();
    assertThat(
            change(
                ana(),
                copy,
                "\"2\"",
                mirrored,
                rule("EXCLUDES", "SEAT_LEATHER", null, null, "AUDIO_PREMIUM", "ROOF_PANORAMIC")))
        .as("a paired rule keeps one target")
        .hasStatus(422);

    assertThat(delete(ana(), copy, "\"2\"", keyOf("AUDIO_PREMIUM", "EXCLUDES"))).hasStatusOk();
    assertThat(sentences(copy))
        .as("deleting either rule deletes both")
        .containsExactlyInAnyOrder(
            "Manual Transmission excludes Panoramic Roof (on Sport; in Europe)",
            "Panoramic Roof excludes Manual Transmission (on Sport; in Europe)");
  }

  @Test
  void aRuleNamesOnlyActiveFeatureRowsTrimsAndRegionsOfTheCatalog() {
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'AUDIO_PREMIUM'").update();
    jdbc.sql("UPDATE trim SET active = false WHERE name = 'Touring'").update();
    jdbc.sql("UPDATE region SET active = false WHERE code = 'NA'").update();
    assertThat(
            add(ana(), copy, "\"0\"", rule("REQUIRES", "PACKAGE_TOW", null, null, "TRANS_MANUAL")))
        .hasStatusOk();
    var refused =
        Map.ofEntries(
            Map.entry(
                "a feature that is no feature row",
                rule("REQUIRES", "SEAT_LEATHER", null, null, "ROOF_REMOVABLE")),
            Map.entry(
                "a retired feature", rule("REQUIRES", "SEAT_LEATHER", null, null, "AUDIO_PREMIUM")),
            Map.entry(
                "a trim the catalog does not have",
                rule("REQUIRES", "SEAT_LEATHER", List.of("Luxury"), null, "ROOF_PANORAMIC")),
            Map.entry(
                "an inactive trim",
                rule("REQUIRES", "SEAT_LEATHER", List.of("Touring"), null, "ROOF_PANORAMIC")),
            Map.entry(
                "a region the catalog does not have",
                rule("REQUIRES", "SEAT_LEATHER", null, List.of("ASIA"), "ROOF_PANORAMIC")),
            Map.entry(
                "an inactive region",
                rule("REQUIRES", "SEAT_LEATHER", null, List.of("NA"), "ROOF_PANORAMIC")),
            Map.entry(
                "a scope that lists nothing",
                rule("REQUIRES", "SEAT_LEATHER", List.of(), null, "ROOF_PANORAMIC")),
            Map.entry(
                "a rule that is there already",
                rule("REQUIRES", "PACKAGE_TOW", null, null, "TRANS_MANUAL")),
            Map.entry(
                "its source among its targets",
                rule("REQUIRES", "SEAT_LEATHER", null, null, "SEAT_LEATHER")),
            Map.entry(
                "one target for a Requires one of",
                rule("REQUIRES_ONE_OF", "SEAT_LEATHER", null, null, "ROOF_PANORAMIC")),
            Map.entry(
                "a feature that includes others",
                rule("INCLUDES", "SEAT_LEATHER", null, null, "ROOF_PANORAMIC")),
            Map.entry(
                "no kind",
                rule("REQUIRES", "SEAT_LEATHER", null, null, "ROOF_PANORAMIC")
                    .replace("\"REQUIRES\"", "null")));

    refused.forEach(
        (what, given) ->
            assertThat(add(ana(), copy, "\"1\"", given))
                .as(what)
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("VALIDATION"));
    assertThat(
            change(
                ana(),
                copy,
                "\"1\"",
                keyOf("PACKAGE_TOW", "REQUIRES"),
                rule("INCLUDES", "PACKAGE_TOW", null, null, "TRANS_MANUAL")))
        .as("another kind")
        .hasStatus(422);
    assertThat(revision(copy)).isEqualTo(1);
    assertThat(count("catalog_rule WHERE catalog_id = %d", copy)).isEqualTo(1);
  }

  @Test
  void aCatalogHasAtMostFiveHundredRulesAndARuleTwentyTargets() {
    jdbc.sql(
            """
            INSERT INTO catalog_rule
                (catalog_id, rule_key, kind, source_feature_id, all_trims, all_regions)
            SELECT :copy, gen_random_uuid(), 'REQUIRES', :source, true, true
            FROM generate_series(1, 499)
            """)
        .param("copy", copy)
        .param("source", feature("SEAT_LEATHER"))
        .update();
    var rows =
        jdbc.sql(
                """
                SELECT f.code FROM catalog_feature c JOIN feature f ON f.id = c.feature_id
                WHERE c.catalog_id = :copy AND f.code <> 'PACKAGE_TOW' ORDER BY f.code LIMIT 21
                """)
            .param("copy", copy)
            .query(String.class)
            .list();

    assertThat(
            add(
                ana(),
                copy,
                "\"0\"",
                rule("EXCLUDES", "TRANS_MANUAL", null, null, "ROOF_PANORAMIC")))
        .as("a pair counts as two")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(
            add(
                ana(),
                copy,
                "\"0\"",
                rule("INCLUDES", "PACKAGE_TOW", null, null, rows.toArray(String[]::new))))
        .as("21 targets")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(
            add(
                ana(),
                copy,
                "\"0\"",
                rule(
                    "INCLUDES",
                    "PACKAGE_TOW",
                    null,
                    null,
                    rows.subList(0, 20).toArray(String[]::new))))
        .as("the 500th rule, with 20 targets")
        .hasStatusOk();
    assertThat(
            add(
                ana(),
                copy,
                "\"1\"",
                rule("REQUIRES", "TRANS_MANUAL", null, null, "ROOF_PANORAMIC")))
        .as("a 501st rule")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
  }

  @Test
  void aFeatureRowThatARuleNamesCannotBeRemoved() {
    add(
        ana(),
        copy,
        "\"0\"",
        rule("REQUIRES", "SEAT_LEATHER", List.of("Sport"), null, "AUDIO_PREMIUM"));
    add(ana(), copy, "\"1\"", rule("EXCLUDES", "SEAT_LEATHER", null, null, "TRANS_MANUAL"));

    var asSource = removeFeature(ana(), copy, "\"2\"", "SEAT_LEATHER");
    var asTarget = removeFeature(ana(), copy, "\"2\"", "AUDIO_PREMIUM");

    assertThat(asSource).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("IN_USE");
    assertThat(ApplicationIT.<List<String>>read(asSource, "$.usedBy"))
        .as("a pair once")
        .containsExactly(
            "Leather Seats excludes Manual Transmission",
            "Leather Seats requires Premium Audio (on Sport)");
    assertThat(ApplicationIT.<List<String>>read(asTarget, "$.usedBy"))
        .containsExactly("Leather Seats requires Premium Audio (on Sport)");
    assertThat(revision(copy)).isEqualTo(2);
    assertThat(removeFeature(ana(), copy, "\"2\"", "ROOF_PANORAMIC"))
        .as("a row no rule names")
        .hasStatusOk();
  }

  @Test
  void removingATrimOrARegionTakesItOutOfEveryScopeAndDeletesTheRulesItAloneWasIn() {
    add(
        ana(),
        copy,
        "\"0\"",
        rule("REQUIRES", "SEAT_LEATHER", List.of("Sport"), null, "AUDIO_PREMIUM"));
    add(
        ana(),
        copy,
        "\"1\"",
        rule(
            "REQUIRES", "SEAT_LEATHER", List.of("Sport", "Base"), List.of("EU"), "ROOF_PANORAMIC"));
    add(
        ana(),
        copy,
        "\"2\"",
        rule("EXCLUDES", "TRANS_MANUAL", List.of("Sport"), null, "SEAT_LEATHER"));
    add(ana(), copy, "\"3\"", rule("INCLUDES", "PACKAGE_TOW", null, null, "TRANS_MANUAL"));

    var withoutSport =
        edit(
            ana(),
            mvc.delete().uri("/api/catalogs/{id}/trims/{trim}", copy, trim("Sport")),
            "\"4\"",
            null);

    assertThat(withoutSport).hasStatusOk().bodyJson().extractingPath("$.revision").isEqualTo(5);
    assertThat(ApplicationIT.<List<String>>read(withoutSport, "$.rulesDeleted"))
        .as("a pair once")
        .containsExactly(
            "Leather Seats excludes Manual Transmission (on Sport)",
            "Leather Seats requires Premium Audio (on Sport)");
    assertThat(sentences(copy))
        .containsExactlyInAnyOrder(
            "Leather Seats requires Panoramic Roof (on Base; in Europe)",
            "Tow Package includes Manual Transmission");
    assertThat(count("trim WHERE name = 'Sport' AND active")).isEqualTo(1);
    assertThat(
            count(
                "catalog_trim WHERE trim_id = %d AND catalog_id = %d",
                trim("Sport"), approved("COMPACT_SUV", 2026, 2)))
        .as("other catalogs keep the trim")
        .isEqualTo(1);

    var withoutEurope =
        edit(ana(), mvc.delete().uri("/api/catalogs/{id}/regions/EU", copy), "\"5\"", null);

    assertThat(ApplicationIT.<List<String>>read(withoutEurope, "$.rulesDeleted"))
        .containsExactly("Leather Seats requires Panoramic Roof (on Base; in Europe)");
    assertThat(sentences(copy)).containsExactly("Tow Package includes Manual Transmission");
    var history = mvc.get().uri("/api/catalogs/{id}/changes", copy).with(ana()).exchange();
    assertThat(ApplicationIT.<List<String>>read(history, "$.items[*].kind"))
        .startsWith(
            "REGION_REMOVED", "RULE_REMOVED", "TRIM_REMOVED", "RULE_REMOVED", "RULE_REMOVED");
  }

  @Test
  void theEditingRulesHoldForRules() {
    add(ana(), copy, "\"0\"", rule("REQUIRES", "SEAT_LEATHER", null, null, "AUDIO_PREMIUM"));
    var key = keyOf("SEAT_LEATHER", "REQUIRES");
    var approved = approved("COMPACT_SUV", 2026, 2);
    // The seeded catalogs belong to the demo author.
    var ownerOfApproved = signedInAs(Role.AUTHOR, "author");
    var another = rule("REQUIRES", "SEAT_LEATHER", null, null, "ROOF_PANORAMIC");
    Map<String, Edit> edits =
        Map.of(
            "add a rule", (who, id, revision) -> add(who, id, revision, another),
            "change a rule", (who, id, revision) -> change(who, id, revision, key, another),
            "delete a rule", (who, id, revision) -> delete(who, id, revision, key));

    edits.forEach(
        (what, edit) -> {
          assertThat(edit.send(ana(), copy, null)).as("%s without a revision", what).hasStatus(428);
          assertThat(edit.send(ana(), copy, "\"7\""))
              .as("%s from another revision", what)
              .hasStatus(412)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("REVISION_CONFLICT");
          assertThat(edit.send(ben(), copy, "\"1\"")).as("%s by someone else", what).hasStatus(404);
          assertThat(edit.send(ownerOfApproved, approved, "\"0\""))
              .as("%s of an Approved version", what)
              .hasStatus(409)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("NOT_DRAFT");
        });
    assertThat(revision(copy)).isEqualTo(1);
    assertThat(sentences(copy)).containsExactly("Leather Seats requires Premium Audio");
  }

  private interface Edit {
    MvcTestResult send(RequestPostProcessor who, long catalog, String revision);
  }

  private MvcTestResult add(RequestPostProcessor who, long catalog, String revision, String rule) {
    return edit(who, mvc.post().uri("/api/catalogs/{id}/rules", catalog), revision, rule);
  }

  private MvcTestResult change(
      RequestPostProcessor who, long catalog, String revision, String ruleKey, String rule) {
    return edit(
        who, mvc.put().uri("/api/catalogs/{id}/rules/{key}", catalog, ruleKey), revision, rule);
  }

  private MvcTestResult delete(
      RequestPostProcessor who, long catalog, String revision, String ruleKey) {
    return edit(
        who, mvc.delete().uri("/api/catalogs/{id}/rules/{key}", catalog, ruleKey), revision, null);
  }

  private MvcTestResult removeFeature(
      RequestPostProcessor who, long catalog, String revision, String feature) {
    return edit(
        who,
        mvc.delete().uri("/api/catalogs/{id}/features/{feature}", catalog, feature(feature)),
        revision,
        null);
  }

  /**
   * What an owner gives for a rule, with its features named by code and its trims by name. A scope
   * that is null covers everything.
   */
  private String rule(
      String kind, String source, List<String> trims, List<String> regions, String... targets) {
    return """
        {"kind": "%s", "sourceFeatureId": %d, "targetFeatureIds": %s,
         "allTrims": %s, "trimIds": %s, "allRegions": %s, "regionCodes": %s}
        """
        .formatted(
            kind,
            feature(source),
            Stream.of(targets).map(this::feature).toList(),
            trims == null,
            trims == null ? "[]" : trims.stream().map(this::trim).toList(),
            regions == null,
            regions == null
                ? "[]"
                : regions.stream().collect(Collectors.joining("\", \"", "[\"", "\"]")));
  }

  /** The key of the catalog's one rule of the kind from the source. */
  private String keyOf(String source, String kind) {
    return jdbc.sql(
            """
            SELECT rule_key::text FROM catalog_rule
            WHERE catalog_id = :copy AND source_feature_id = :source AND kind = :kind
            """)
        .param("copy", copy)
        .param("source", feature(source))
        .param("kind", kind)
        .query(String.class)
        .single();
  }

  /**
   * The rules the catalog shows, each as a sentence by the names it shows for its features, trims,
   * and regions.
   */
  private List<String> sentences(long catalog) {
    Map<String, Object> snapshot = read(open(ana(), catalog), "$.snapshot");
    var features = names(snapshot, "featureRows", "id");
    var trims = names(snapshot, "trims", "id");
    var regions = names(snapshot, "regions", "code");

    return CatalogRulesIT.<Map<String, Object>>listOf(snapshot.get("rules")).stream()
        .map(
            rule -> {
              var scopes =
                  Stream.of(
                          (boolean) rule.get("allTrims")
                              ? null
                              : "on " + named(trims, rule.get("trimIds")),
                          (boolean) rule.get("allRegions")
                              ? null
                              : "in " + named(regions, rule.get("regionCodes")))
                      .filter(scope -> scope != null)
                      .collect(Collectors.joining("; "));
              var said =
                  "%s %s %s"
                      .formatted(
                          features.get(rule.get("sourceFeatureId")),
                          rule.get("kind").toString().toLowerCase().replace('_', ' '),
                          named(features, rule.get("targetFeatureIds")));
              return scopes.isEmpty() ? said : "%s (%s)".formatted(said, scopes);
            })
        .toList();
  }

  private static Map<Object, String> names(Map<String, Object> snapshot, String of, String key) {
    return CatalogRulesIT.<Map<String, Object>>listOf(snapshot.get(of)).stream()
        .collect(Collectors.toMap(entry -> entry.get(key), entry -> (String) entry.get("name")));
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> listOf(Object read) {
    return (List<T>) read;
  }

  /** The names of the keys, in the order the rule lists them. */
  private static String named(Map<Object, String> names, Object keys) {
    return CatalogRulesIT.<Object>listOf(keys).stream()
        .map(names::get)
        .collect(Collectors.joining(", "));
  }
}
