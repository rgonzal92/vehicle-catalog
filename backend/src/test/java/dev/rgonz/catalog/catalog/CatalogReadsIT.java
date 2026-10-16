package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.catalog.Catalogs.Read;
import dev.rgonz.catalog.catalog.Catalogs.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks the three ways a catalog is read: whom each opens a catalog for, whether it asks the
 * cache, which names an Approved version reads with, and whether it works out the catalog's issues.
 * Every test starts from the seeded catalogs, which the cache holds nothing of.
 */
class CatalogReadsIT extends WorkingCopyTests {
  @Autowired Catalogs catalogs;
  @Autowired ApprovedContents cache;

  /** A Draft, a Submitted catalog, and an Approved version, by what they are. */
  private final Map<String, Long> kinds = new LinkedHashMap<>();

  /** Its owner, a reviewer, another author, and the system, by who they are. */
  private final Map<String, Reader> readers = new LinkedHashMap<>();

  /** The trim named Base, which is renamed after the Approved version was approved. */
  private long base;

  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
    var submitted = workingCopy(ana(), "SEDAN", 2027);
    jdbc.sql("UPDATE catalog SET status = 'SUBMITTED', submitted_at = now() WHERE id = :id")
        .param("id", submitted)
        .update();
    kinds.put("a Draft", workingCopy(ana(), "COMPACT_SUV", 2026));
    kinds.put("a Submitted catalog", submitted);
    kinds.put("an Approved version", approved("COMPACT_SUV", 2026, 2));
    readers.put("its owner", new Reader.Person(person("ana"), false));
    readers.put("a reviewer", new Reader.Person(person("mia"), true));
    readers.put("another author", new Reader.Person(person("ben"), false));
    readers.put("the system", Reader.SYSTEM);
    base = trim("Base");
    jdbc.sql("UPDATE trim SET name = 'Entry' WHERE id = :id").param("id", base).update();
  }

  @Test
  void eachReadOpensACatalogForWhoMayReadItAndOnlyShowingItWorksOutItsIssues() {
    var validations = meters.get("catalog.validate").timer();
    var found = new ArrayList<String>();

    for (var kind : kinds.entrySet()) {
      for (var reader : readers.entrySet()) {
        for (var shown : List.of(true, false)) {
          var asked = askedOfTheCache();
          var validated = validations.count();
          var read =
              shown
                  ? catalogs.shown(kind.getValue(), reader.getValue()).map(CatalogView::read)
                  : catalogs.contents(kind.getValue(), reader.getValue());
          found.add(
              "%s %s by %s: %s"
                  .formatted(
                      shown ? "shown" : "contents",
                      kind.getKey(),
                      reader.getKey(),
                      outcome(read, askedOfTheCache() > asked, validations.count() > validated)));
          if (!shown) {
            assertThat(catalogs.opensFor(kind.getValue(), reader.getValue()))
                .as("whether %s opens for %s", kind.getKey(), reader.getKey())
                .isEqualTo(read.isPresent());
          }
        }
      }
    }

    var draft = "found, does not ask the cache, %s";
    var approved = "found, asks the cache, %s, named Base";
    assertThat(found)
        .containsExactly(
            "shown a Draft by its owner: " + draft.formatted("validates"),
            "contents a Draft by its owner: " + draft.formatted("does not validate"),
            "shown a Draft by a reviewer: not found",
            "contents a Draft by a reviewer: not found",
            "shown a Draft by another author: not found",
            "contents a Draft by another author: not found",
            "shown a Draft by the system: " + draft.formatted("validates"),
            "contents a Draft by the system: " + draft.formatted("does not validate"),
            "shown a Submitted catalog by its owner: " + draft.formatted("validates"),
            "contents a Submitted catalog by its owner: " + draft.formatted("does not validate"),
            "shown a Submitted catalog by a reviewer: " + draft.formatted("validates"),
            "contents a Submitted catalog by a reviewer: " + draft.formatted("does not validate"),
            "shown a Submitted catalog by another author: not found",
            "contents a Submitted catalog by another author: not found",
            "shown a Submitted catalog by the system: " + draft.formatted("validates"),
            "contents a Submitted catalog by the system: " + draft.formatted("does not validate"),
            "shown an Approved version by its owner: " + approved.formatted("validates"),
            "contents an Approved version by its owner: " + approved.formatted("does not validate"),
            "shown an Approved version by a reviewer: " + approved.formatted("validates"),
            "contents an Approved version by a reviewer: "
                + approved.formatted("does not validate"),
            "shown an Approved version by another author: " + approved.formatted("validates"),
            "contents an Approved version by another author: "
                + approved.formatted("does not validate"),
            "shown an Approved version by the system: " + approved.formatted("validates"),
            "contents an Approved version by the system: "
                + approved.formatted("does not validate"));
  }

  @Test
  void anApprovedVersionAsLabelledTodayHasTheLibrarysNamesOfTodayAndIsNotReadFromTheCache() {
    var asked = askedOfTheCache();

    var today = catalogs.asLabelledToday(kinds.get("an Approved version"));

    assertThat(nameOf(base, today.trims())).isEqualTo("Entry");
    assertThat(askedOfTheCache()).as("reads that asked the cache").isEqualTo(asked);
  }

  @Test
  void showingACatalogGivesTheIssuesValidationFindsInItAgainstTheLibraryOfToday() {
    var draft = kinds.get("a Draft");

    var shown = catalogs.shown(draft, readers.get("its owner")).orElseThrow();

    assertThat(shown.issues())
        .isEqualTo(Validation.issues(shown.read().snapshot(), catalogs.library(draft)));
  }

  /** How many reads have asked the cache so far, whatever it answered. */
  private long askedOfTheCache() {
    var counts = cache.counts();
    return counts.hits() + counts.misses() + counts.fallbacks();
  }

  private String outcome(Optional<Read> read, boolean askedTheCache, boolean validated) {
    return read.map(
            found ->
                "found, %s the cache, %s%s"
                    .formatted(
                        askedTheCache ? "asks" : "does not ask",
                        validated ? "validates" : "does not validate",
                        found.snapshot().status() == CatalogSnapshot.Status.APPROVED
                            ? ", named " + nameOf(base, found.snapshot().trims())
                            : ""))
        .orElse("not found");
  }

  private static String nameOf(long trim, List<Trim> trims) {
    return trims.stream().filter(it -> it.id() == trim).findFirst().orElseThrow().name();
  }
}
