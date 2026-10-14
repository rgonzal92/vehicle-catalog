package dev.rgonz.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Checks that the backend starts on a database that holds a migration it does not know, which is
 * what a release that is taken back leaves behind: the database stays as the new image made it, and
 * the image before runs on it. The database is this test's own, in the server the tests share. It
 * is migrated with the build's migrations and with one later than all of them, which adds a table
 * and is kept where only this test looks. The app is then started on it as the build is.
 */
@Import(LaterMigrationIT.ADatabaseOfALaterRelease.class)
class LaterMigrationIT extends ApplicationIT {
  static final String NAME = "a_later_release";
  static final String URL =
      DATABASE.getJdbcUrl().replace("/" + DATABASE.getDatabaseName(), "/" + NAME);

  static {
    try (var server =
            DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var statement = server.createStatement()) {
      statement.execute("CREATE DATABASE " + NAME);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    Flyway.configure()
        .dataSource(URL, DATABASE.getUsername(), DATABASE.getPassword())
        .locations("classpath:db/migration", "classpath:db/later-release")
        .load()
        .migrate();
  }

  @Test
  void itIsReadyAndTheHistoryStillHoldsTheLaterMigrationAndItsTable() {
    assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo(NAME);
    assertThat(mvc.get().uri("/api/health/readiness")).hasStatusOk();
    assertThat(
            jdbc.sql("SELECT version FROM flyway_schema_history WHERE version = '999' AND success")
                .query(String.class)
                .single())
        .isEqualTo("999");
    assertThat(jdbc.sql("SELECT count(*) FROM later_release").query(Long.class).single()).isZero();
  }

  /**
   * The database the app and its Flyway connect to: this test's own, before the one the shared
   * container gives every other test.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class ADatabaseOfALaterRelease {
    @Bean
    @Primary
    OwnDatabase ownDatabase() {
      return new OwnDatabase();
    }
  }

  static class OwnDatabase implements JdbcConnectionDetails, FlywayConnectionDetails {
    @Override
    public String getJdbcUrl() {
      return URL;
    }

    @Override
    public String getUsername() {
      return DATABASE.getUsername();
    }

    @Override
    public String getPassword() {
      return DATABASE.getPassword();
    }

    @Override
    public String getDriverClassName() {
      return DATABASE.getDriverClassName();
    }
  }
}
