package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

/** Checks when the seeded catalogs are loaded and when they are left out. */
class CatalogSeedIT extends ApplicationIT {
  @Autowired CatalogSeed seed;

  @Test
  void aLibraryThatIsNotTheSeededOneGetsNoCatalogsAndTheStartGoesOn() throws Exception {
    seedLibraryAndCatalogs();
    jdbc.sql("TRUNCATE lineage, catalog CASCADE").update();
    jdbc.sql("UPDATE trim SET name = 'Entry' WHERE name = 'Base'").update();

    seed.run(new DefaultApplicationArguments());

    assertThat(count("catalog")).isZero();
    assertThat(count("lineage")).isZero();
  }

  @Test
  void aDatabaseWithoutCatalogsGetsTheSeededOnesAndOneWithCatalogsIsLeftAlone() throws Exception {
    seedLibraryAndCatalogs();
    var seeded = count("catalog");
    assertThat(seeded).isPositive();

    seed.run(new DefaultApplicationArguments());
    assertThat(count("catalog")).isEqualTo(seeded);

    jdbc.sql("TRUNCATE lineage, catalog CASCADE").update();
    seed.run(new DefaultApplicationArguments());
    assertThat(count("catalog")).isEqualTo(seeded);
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
