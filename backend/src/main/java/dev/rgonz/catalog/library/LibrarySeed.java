package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.Seed;
import dev.rgonz.catalog.library.Features.NewFeature;
import dev.rgonz.catalog.library.GlobalRules.RuleContent;
import dev.rgonz.catalog.library.Regions.NewRegion;
import dev.rgonz.catalog.library.Trims.NewTrim;
import dev.rgonz.catalog.vehicleline.VehicleLines;
import dev.rgonz.catalog.vehicleline.VehicleLines.NewVehicleLine;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives an empty library its starting trims, regions, vehicle lines, features, and global rules, so
 * the app is usable the moment it runs. The entries come from five files under {@code seed/}, one
 * for each list, written as the admin endpoints take them, and each entry is held to those
 * endpoints' rules. A global rule names its features by their codes. It runs before the seed of the
 * catalogs, which are made of these entries.
 */
@Component
@Order(1)
class LibrarySeed implements Seed {
  private final Trims trims;
  private final Regions regions;
  private final VehicleLines vehicleLines;
  private final Features features;
  private final GlobalRules globalRules;
  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Validator validator;

  LibrarySeed(
      Trims trims,
      Regions regions,
      VehicleLines vehicleLines,
      Features features,
      GlobalRules globalRules,
      JdbcClient jdbc,
      JsonMapper json,
      Validator validator) {
    this.trims = trims;
    this.regions = regions;
    this.vehicleLines = vehicleLines;
    this.features = features;
    this.globalRules = globalRules;
    this.jdbc = jdbc;
    this.json = json;
    this.validator = validator;
  }

  /**
   * Runs once the application has started, and in each demo reset. The whole load is one
   * transaction, so an entry that is refused leaves the library as it was and the next run tries
   * again.
   */
  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    loadIfEmpty();
  }

  /** Loads the seeded entries, unless the library already holds anything at all. */
  @Transactional
  void loadIfEmpty() {
    if (!isEmpty()) {
      return;
    }
    load("trims.json", NewTrim.class, trims::add);
    load("regions.json", NewRegion.class, regions::add);
    load("vehicle-lines.json", NewVehicleLine.class, vehicleLines::add);
    load("features.json", NewFeature.class, features::add);
    load("global-rules.json", SeededRule.class, rule -> globalRules.add(rule.content(this::id)));
  }

  /** The feature with the code, which a seeded rule names it by. */
  private long id(String featureCode) {
    return jdbc.sql("SELECT id FROM feature WHERE code = :code")
        .param("code", featureCode)
        .query(Long.class)
        .single();
  }

  /**
   * A global rule as its seed file writes it, with its features by their codes.
   *
   * @param regions the regions the rule applies in, or null when it applies in every region
   */
  record SeededRule(RuleKind kind, String source, List<String> targets, List<String> regions) {
    RuleContent content(Function<String, Long> idOf) {
      return new RuleContent(
          kind, idOf.apply(source), targets.stream().map(idOf).toList(), regions == null, regions);
    }
  }

  private boolean isEmpty() {
    return jdbc.sql(
            """
            SELECT NOT EXISTS (SELECT 1 FROM trim)
               AND NOT EXISTS (SELECT 1 FROM region)
               AND NOT EXISTS (SELECT 1 FROM vehicle_line)
               AND NOT EXISTS (SELECT 1 FROM feature)
            """)
        .query(Boolean.class)
        .single();
  }

  private <T> void load(String file, Class<T> type, Consumer<T> add) {
    for (var entry : read(file, type)) {
      var broken = validator.validate(entry);
      if (!broken.isEmpty()) {
        throw new IllegalStateException("seed/%s: %s breaks %s".formatted(file, entry, broken));
      }
      try {
        add.accept(entry);
      } catch (RuntimeException refused) {
        throw new IllegalStateException("seed/%s: %s was refused".formatted(file, entry), refused);
      }
    }
  }

  /** The entries of one seed file, in the file's order. */
  <T> List<T> read(String file, Class<T> type) {
    try (var content = new ClassPathResource("seed/" + file).getInputStream()) {
      return json.readValue(
          content, json.getTypeFactory().constructCollectionType(List.class, type));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }
}
