package dev.rgonz.catalog.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.library.Rules.Content;
import dev.rgonz.catalog.library.Rules.Nameable;
import dev.rgonz.catalog.library.Rules.NamedFeature;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * Checks that a rule is refused for the first thing wrong with what it names, in the same words for
 * a global rule as for a catalog rule, apart from where a rule may name from. The library and the
 * catalog both have the Tow Package, Heavy-Duty Cooling, the Trailer Hitch Receiver, and the
 * retired Trailer Wiring, and the regions North America and Europe active and South America not.
 * The catalog has the trims Base and Sport active and Touring not.
 */
class RulesTest {
  private static final long TOW = 8;
  private static final long COOLING = 9;
  private static final long HITCH = 10;
  private static final long WIRING = 11;

  /** A feature that neither has. */
  private static final long ELSEWHERE = 12;

  private static final long BASE = 1;
  private static final long SPORT = 2;
  private static final long TOURING = 3;

  private static final List<NamedFeature> FEATURES =
      List.of(
          new NamedFeature(TOW, "Tow Package", "PACKAGE", "ACTIVE"),
          new NamedFeature(COOLING, "Heavy-Duty Cooling", "FEATURE", "ACTIVE"),
          new NamedFeature(HITCH, "Trailer Hitch Receiver", "FEATURE", "ACTIVE"),
          new NamedFeature(WIRING, "Trailer Wiring", "FEATURE", "RETIRED"));
  private static final Set<String> ACTIVE_REGIONS = Set.of("NA", "EU");

  private static final Nameable LIBRARY =
      new Nameable(
          ids -> FEATURES.stream().filter(feature -> ids.contains(feature.id())).toList(),
          "Choose features from the library.",
          null,
          null,
          codes -> codes.stream().filter(ACTIVE_REGIONS::contains).count(),
          "Choose active regions from the library.");
  private static final Nameable CATALOG =
      new Nameable(
          LIBRARY.features(),
          "Choose features from the catalog's feature rows.",
          ids -> ids.stream().filter(Set.of(BASE, SPORT)::contains).count(),
          "Choose active trims of the catalog.",
          LIBRARY.activeRegions(),
          "Choose active regions of the catalog.");

  /** What a rule names. A scope that is null covers everything. */
  private static Content content(
      RuleKind kind, Long source, List<Long> targets, List<Long> trims, List<String> regions) {
    return new Content(kind, source, targets, trims, regions);
  }

  private static Content requires(Long... targets) {
    return content(RuleKind.REQUIRES, TOW, Arrays.asList(targets), null, null);
  }

  private static Content inRegions(String... regions) {
    return content(RuleKind.REQUIRES, TOW, List.of(COOLING), null, Arrays.asList(regions));
  }

  /** The refusal of the content, as the origin gives it. */
  private static ApiException refusalOf(Content given, Nameable origin) {
    return catchThrowableOfType(ApiException.class, () -> Rules.checkWhatARuleNames(given, origin));
  }

  private record Refused(String what, Content given, String refusal) {}

  @Test
  void bothOriginsRefuseWhatARuleMayNotNameInTheSameWordsTheFirstThingWrongFirst() {
    var twentyOne = LongStream.rangeClosed(100, 120).boxed().toArray(Long[]::new);
    var refusals =
        List.of(
            new Refused(
                "no kind", content(null, TOW, List.of(COOLING), null, null), "Choose a kind."),
            new Refused(
                "nothing given but an empty region scope",
                content(null, null, null, null, List.of()),
                "Choose a kind."),
            new Refused(
                "no source",
                content(RuleKind.REQUIRES, null, List.of(COOLING), null, null),
                "Choose a source."),
            new Refused(
                "no targets",
                content(RuleKind.REQUIRES, TOW, null, null, null),
                "Choose at least one target."),
            new Refused("a null target", requires(COOLING, null), "Choose at least one target."),
            new Refused("an empty list of targets", requires(), "Choose at least one target."),
            new Refused(
                "too many targets, which neither origin has",
                requires(twentyOne),
                "A rule has at most 20 targets."),
            new Refused(
                "one target for a Requires one of",
                content(RuleKind.REQUIRES_ONE_OF, TOW, List.of(COOLING), null, null),
                "A Requires one of rule has at least 2 targets."),
            new Refused(
                "a target named twice, which is retired",
                requires(WIRING, WIRING),
                "Name each target once."),
            new Refused(
                "its source among its targets",
                requires(TOW, COOLING),
                "A rule's source cannot be one of its targets."),
            new Refused(
                "a retired feature",
                requires(WIRING),
                "Trailer Wiring is retired, and a rule cannot name it."),
            new Refused(
                "a feature that is no package includes another",
                content(RuleKind.INCLUDES, COOLING, List.of(HITCH), null, null),
                "Only a package includes other features."),
            new Refused(
                "a region scope that lists nothing",
                inRegions(),
                "Choose the regions the rule applies in, or every region."));

    for (var refused : refusals) {
      for (var origin : List.of(LIBRARY, CATALOG)) {
        assertThat(refusalOf(refused.given(), origin))
            .as("%s, from %s", refused.what(), of(origin))
            .isNotNull()
            .extracting(refusal -> refusal.getBody().getDetail())
            .isEqualTo(refused.refusal());
      }
    }
    assertThat(refusalOf(requires(twentyOne), CATALOG).getBody().getProperties())
        .containsEntry("code", "LIMIT_EXCEEDED");
    assertThat(refusalOf(requires(WIRING), LIBRARY).getBody().getProperties())
        .containsEntry("code", "VALIDATION");
  }

  @Test
  void eachOriginRefusesInItsOwnWordsWhatItDoesNotHaveForARuleToName() {
    for (var origin : List.of(LIBRARY, CATALOG)) {
      assertThat(refusalOf(requires(ELSEWHERE), origin).getBody().getDetail())
          .as("a feature %s does not have", of(origin))
          .isEqualTo(origin.notAFeature());
      assertThat(refusalOf(inRegions("EU", "ZZ"), origin).getBody().getDetail())
          .as("a region %s does not have", of(origin))
          .isEqualTo(origin.notARegion());
      assertThat(refusalOf(inRegions("SA"), origin).getBody().getDetail())
          .as("a region inactive in %s", of(origin))
          .isEqualTo(origin.notARegion());
    }
    assertThat(refusalOf(inRegions("EU", null), LIBRARY).getBody().getDetail())
        .as("a null region code: a catalog rule drops it before the check, a global rule does not")
        .isEqualTo(LIBRARY.notARegion());
  }

  private static String of(Nameable origin) {
    return origin == LIBRARY ? "the library" : "the catalog";
  }

  @Test
  void aCatalogRuleIsRefusedATrimScopeThatListsNothingOrWhatTheCatalogHasNotActive() {
    var nothing = content(RuleKind.REQUIRES, TOW, List.of(COOLING), List.of(), null);
    var touring = content(RuleKind.REQUIRES, TOW, List.of(COOLING), List.of(SPORT, TOURING), null);

    assertThat(refusalOf(nothing, CATALOG).getBody().getDetail())
        .isEqualTo("Choose the trims the rule covers, or every trim.");
    assertThat(refusalOf(touring, CATALOG).getBody().getDetail())
        .isEqualTo("Choose active trims of the catalog.");
  }

  @Test
  void whatARuleMayNameIsLetThrough() {
    var tow = content(RuleKind.INCLUDES, TOW, List.of(COOLING, HITCH), null, List.of("NA", "EU"));
    var onTrims = content(RuleKind.REQUIRES, COOLING, List.of(HITCH), List.of(BASE, SPORT), null);

    assertThatCode(() -> Rules.checkWhatARuleNames(tow, LIBRARY)).doesNotThrowAnyException();
    assertThatCode(() -> Rules.checkWhatARuleNames(tow, CATALOG)).doesNotThrowAnyException();
    assertThatCode(() -> Rules.checkWhatARuleNames(onTrims, CATALOG)).doesNotThrowAnyException();
  }
}
