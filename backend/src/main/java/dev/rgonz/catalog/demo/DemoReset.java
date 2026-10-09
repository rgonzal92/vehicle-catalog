package dev.rgonz.catalog.demo;

import dev.rgonz.catalog.core.Seed;
import dev.rgonz.catalog.user.SandboxRoles;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo reset: every day at 03:00 UTC the library and the catalogs go back to what a first start
 * leaves, so that nothing a visitor did lasts longer than a day. Every working copy, every Approved
 * version that is not seeded, and all change history go, whoever they belong to. The people who
 * have signed in stay on record, and so do the accounts at the login provider.
 *
 * <p>The API runs it. The worker, which is the same build, does not: one reset a day is enough.
 */
@Component
@Profile("!worker")
class DemoReset {
  private static final Logger log = LoggerFactory.getLogger(DemoReset.class);

  /** For how long the reset goes on asking for the tables while a request is using one. */
  private static final Duration PATIENCE = Duration.ofMinutes(1);

  private static final Duration PAUSE = Duration.ofMillis(200);

  /** PostgreSQL's codes for the two ways a table can be in use. */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  private static final String DEADLOCK_DETECTED = "40P01";

  /**
   * The tables that hold the library and the catalogs, and every table that refers to one of them,
   * whatever is added later: what a catalog is made of and its change history.
   */
  private static final String CONTENT_TABLES =
      """
      WITH RECURSIVE content AS (
          SELECT oid FROM pg_class
          WHERE relnamespace = current_schema()::regnamespace
            AND relname IN ('lineage', 'catalog', 'vehicle_line', 'trim', 'region', 'feature')
          UNION
          SELECT referring.conrelid
          FROM pg_constraint referring
          JOIN content ON referring.confrelid = content.oid
          WHERE referring.contype = 'f'
      )
      SELECT string_agg(oid::regclass::text, ', ' ORDER BY oid::regclass::text) FROM content
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final List<Seed> seeds;
  private final SandboxRoles sandboxRoles;

  DemoReset(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      List<Seed> seeds,
      SandboxRoles sandboxRoles,
      @Value("${app.demo-reset.cron}") String schedule) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.seeds = seeds;
    this.sandboxRoles = sandboxRoles;
    if (schedule.equals(Scheduled.CRON_DISABLED)) {
      log.warn("The demo reset is turned off: what visitors do here stays");
    } else {
      log.info("The demo reset runs at \"{}\" (second, minute, hour, ...) in UTC", schedule);
    }
  }

  /**
   * Puts the library and the catalogs back, and then the roles of the sandbox accounts. Each is
   * done whatever becomes of the other: the database can fail where the login provider did not, and
   * the other way round.
   */
  @Scheduled(cron = "${app.demo-reset.cron}", zone = "UTC")
  void run() {
    try {
      restoreTheLibraryAndTheCatalogs();
      log.info("The library and the catalogs are back as a first start leaves them");
    } catch (RuntimeException failure) {
      log.error("The library and the catalogs were not reset", failure);
    }

    sandboxRoles.restore();
  }

  /**
   * Empties the library and the catalogs and seeds them again, in one transaction.
   *
   * <p>It first takes every table for itself, and only if all of them are free at that moment.
   * While a request is in the middle of using one, the reset holds nothing and asks again shortly,
   * so the two never wait for each other in a circle. Once it has the tables, a request that
   * arrives waits and then finds the seeded state. Emptying a table leaves the counter behind its
   * ids where it is, so no id is ever used twice.
   *
   * <p>One kind of request does not find the seeded state: one that reads as of the moment it
   * arrived, as reading a whole catalog does. If it arrives while the tables are held, it finds
   * them empty and answers that there is no such catalog, which is true of every catalog it could
   * have asked for.
   */
  private void restoreTheLibraryAndTheCatalogs() {
    var patienceEnds = Instant.now().plus(PATIENCE);
    while (true) {
      try {
        transactions.executeWithoutResult(
            transaction -> {
              var tables = jdbc.sql(CONTENT_TABLES).query(String.class).single();
              jdbc.sql("LOCK TABLE " + tables + " IN ACCESS EXCLUSIVE MODE NOWAIT").update();
              jdbc.sql("TRUNCATE " + tables).update();
              for (var seed : seeds) {
                try {
                  seed.run(new DefaultApplicationArguments());
                } catch (Exception failure) {
                  throw new IllegalStateException("The demo could not be seeded again", failure);
                }
              }
            });
        return;
      } catch (DataAccessException failure) {
        if (!inUse(failure) || Instant.now().isAfter(patienceEnds)) {
          throw failure;
        }
        pause();
      }
    }
  }

  /**
   * Whether the failure is PostgreSQL saying that a table could not be had at once, or that the
   * reset and a request stood in each other's way after all.
   */
  private static boolean inUse(DataAccessException failure) {
    return failure.getMostSpecificCause() instanceof SQLException refused
        && List.of(LOCK_NOT_AVAILABLE, DEADLOCK_DETECTED).contains(refused.getSQLState());
  }

  private static void pause() {
    try {
      Thread.sleep(PAUSE);
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("The demo reset was stopped while it waited", stopped);
    }
  }
}
