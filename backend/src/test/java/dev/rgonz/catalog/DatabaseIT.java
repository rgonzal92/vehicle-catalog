package dev.rgonz.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Checks what the database the tests run against is: the PostgreSQL the host runs, with pgvector.
 */
class DatabaseIT extends ApplicationIT {
  @Test
  void itIsTheHostsPostgresqlAndHasPgvectorToCreate() {
    assertThat(jdbc.sql("SHOW server_version").query(String.class).single()).startsWith("18.6");
    assertThat(
            jdbc.sql("SELECT default_version FROM pg_available_extensions WHERE name = 'vector'")
                .query(String.class)
                .single())
        .isEqualTo("0.8.7");
    // Nothing creates it yet: the migration that first keeps a meaning does.
    assertThat(
            jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
                .query(Long.class)
                .single())
        .isZero();
  }
}
