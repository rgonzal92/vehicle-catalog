package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.catalog.ApprovedContents.Counts;
import dev.rgonz.catalog.catalog.Catalogs.Reader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the application whose address for the cache is set to nothing, which is no address: it
 * starts, and it reads Approved versions from the database without asking a cache for them, the
 * same every time.
 */
@TestPropertySource(properties = "app.cache.host=")
class WithoutACacheIT extends WorkingCopyTests {
  @Autowired ApprovedContents contents;
  @Autowired Catalogs catalogs;
  @Autowired TransactionTemplate transactions;

  @Test
  void theApplicationStartsAndItsReadsCountAsNeitherHitsNorMisses() throws Exception {
    seedLibraryAndCatalogs();
    var approved = approved("COMPACT_SUV", 2026, 2);

    var first = ApplicationIT.<Map<String, Object>>read(open(ana(), approved), "$.snapshot");
    var second = ApplicationIT.<Map<String, Object>>read(open(ana(), approved), "$.snapshot");

    assertThat(second).isEqualTo(first).containsEntry("status", "APPROVED");
    assertThat(contents.counts()).isEqualTo(new Counts(0, 0, 0));
    var health = mvc.get().uri("/api/health").exchange();
    assertThat(health).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("UP");
    assertThat(health)
        .bodyJson()
        .extractingPath("$.components.cache.status")
        .as("the cache, which there is none of")
        .isEqualTo("UNKNOWN");
  }

  /**
   * PostgreSQL gives a catalog's rows in the order of their key when it reads them along the key's
   * index, and in the order they are stored in when it reads the table. Which it does it decides by
   * what it knows of the table, and autovacuum renews that while the app runs. Here the version's
   * offerings and cells are stored with the last key first, and PostgreSQL is told to read them
   * each way in turn. Nothing of it outlasts the test.
   */
  @Test
  void anApprovedVersionReadsTheSameAlongTheIndexesOfItsKeysAndAsItsRowsAreStored()
      throws Exception {
    seedLibraryAndCatalogs();
    var approved = approved("COMPACT_SUV", 2026, 2);
    var anyone = new Reader.Person(person("ana"), false);

    transactions.executeWithoutResult(
        transaction -> {
          transaction.setRollbackOnly();
          storeLastKeyFirst(approved);

          readTablesOnlyBy("indexscan");
          var alongTheIndexes = catalogs.shown(approved, anyone).orElseThrow();
          readTablesOnlyBy("seqscan");
          var asStored = catalogs.shown(approved, anyone).orElseThrow();

          assertThat(asStored)
              .as("the version read as its rows are stored, against it read along its indexes")
              .isEqualTo(alongTheIndexes);
        });
  }

  /**
   * Stores the catalog's offerings and cells anew, each with its last key first. An offering that
   * goes takes its cells with it, so the cells are set aside before.
   */
  private void storeLastKeyFirst(long catalog) {
    jdbc.sql(
            """
            CREATE TEMPORARY TABLE cell_set_aside ON COMMIT DROP AS
            SELECT * FROM catalog_cell WHERE catalog_id = %d
            """
                .formatted(catalog))
        .update();
    jdbc.sql(
            """
            WITH offering AS (
                DELETE FROM catalog_trim_region WHERE catalog_id = :id RETURNING *
            )
            INSERT INTO catalog_trim_region
            SELECT * FROM offering ORDER BY trim_id DESC, region_code DESC
            """)
        .param("id", catalog)
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_cell
            SELECT * FROM cell_set_aside ORDER BY feature_id DESC, trim_id DESC, region_code DESC
            """)
        .update();
  }

  /**
   * Leaves PostgreSQL one way to read a table until the transaction ends, and has it plan each
   * statement when it is sent: a plan it kept for a statement the connection has sent often was
   * made before it was told.
   */
  private void readTablesOnlyBy(String way) {
    jdbc.sql("SET LOCAL plan_cache_mode = force_custom_plan").update();
    for (var each : List.of("indexscan", "bitmapscan", "seqscan")) {
      jdbc.sql("SET LOCAL enable_%s = %s".formatted(each, each.equals(way) ? "on" : "off"))
          .update();
    }
  }
}
