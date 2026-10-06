package dev.rgonz.catalog.library;

import dev.rgonz.catalog.library.Features.NewFeature;
import dev.rgonz.catalog.library.Regions.NewRegion;
import dev.rgonz.catalog.library.Trims.NewTrim;
import dev.rgonz.catalog.vehicleline.VehicleLines;
import dev.rgonz.catalog.vehicleline.VehicleLines.NewVehicleLine;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives an empty library its starting trims, regions, vehicle lines, and features, so the app is
 * usable the moment it runs. The entries come from four files under {@code seed/}, one for each
 * list, written as the admin endpoints take them, and each entry is held to those endpoints' rules.
 * It runs before the seed of the catalogs, which are made of these entries.
 */
@Component
@Order(1)
class LibrarySeed implements ApplicationRunner {
  private final Trims trims;
  private final Regions regions;
  private final VehicleLines vehicleLines;
  private final Features features;
  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Validator validator;

  LibrarySeed(
      Trims trims,
      Regions regions,
      VehicleLines vehicleLines,
      Features features,
      JdbcClient jdbc,
      JsonMapper json,
      Validator validator) {
    this.trims = trims;
    this.regions = regions;
    this.vehicleLines = vehicleLines;
    this.features = features;
    this.jdbc = jdbc;
    this.json = json;
    this.validator = validator;
  }

  /**
   * Runs once the application has started. The whole load is one transaction, so an entry that is
   * refused leaves the library empty and the next start tries again.
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
