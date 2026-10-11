package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.core.ResetCleanup;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * The contents of Approved versions, kept in the cache so that a read need not ask the database for
 * them. An Approved version's contents never change, so what the cache holds for one is what the
 * database holds. They are kept by the catalog's id, as they read with the labels of its approval,
 * for a day.
 *
 * <p>The cache is never needed. When it does not answer, the contents are read from the database,
 * and for some seconds after that the cache is not asked at all. Without an address for it there is
 * no cache, and every read is of the database.
 *
 * <p>It is also the part of the app's health that says whether the cache answers. Whether the app
 * is ready does not depend on it.
 *
 * <p>The demo reset empties the cache of what is kept here. No entry would be wrong without that: a
 * catalog's id is never used twice, so the entries of catalogs that are gone are never read. It
 * keeps them from taking up the cache's memory until they are a day old.
 */
@Component("cache")
class ApprovedContents implements HealthIndicator, ResetCleanup {
  private static final Logger log = LoggerFactory.getLogger(ApprovedContents.class);

  /**
   * What the key of an entry starts with. The number names the form the contents are kept in: when
   * that form changes, so does the number, and what was kept in the form before is not read.
   */
  static final String KEY = "approved-contents:1:";

  /** What the key of an entry starts with, in whatever form its contents are kept. */
  private static final String IN_ANY_FORM = "approved-contents:";

  private static final Duration KEPT_FOR = Duration.ofDays(1);

  /** How long the cache is left alone after it did not answer, before it is asked again. */
  private static final Duration LEFT_ALONE_FOR = Duration.ofSeconds(10);

  /** A rule's trims and regions are kept in their order, and are read back in it. */
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .addModule(new SimpleModule().addAbstractTypeMapping(Set.class, LinkedHashSet.class))
          .build();

  private final StringRedisTemplate cache;
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private final LongAdder fallbacks = new LongAdder();
  private final AtomicBoolean down = new AtomicBoolean();

  /**
   * What the reads of a version wait their turn on, so that one of them at a time asks the database
   * for it. Two versions may share one, and then wait for each other, which costs a little time and
   * nothing else.
   */
  private final Object[] oneAtATime = Stream.generate(Object::new).limit(64).toArray();

  private volatile long leftAloneUntil;

  ApprovedContents(
      @Value("${app.cache.host}") String host, StringRedisTemplate cache, MeterRegistry metrics) {
    this.cache = host.isBlank() ? null : cache;
    Map.of("hit", hits, "miss", misses, "fallback", fallbacks)
        .forEach(
            (result, counted) ->
                FunctionCounter.builder("cache.read", counted, LongAdder::sum)
                    .description(
                        "How many reads of an Approved version's contents the cache answered (hit),"
                            + " had nothing for (miss), or failed (fallback)")
                    .tag("result", result)
                    .register(metrics));
  }

  /**
   * An Approved version's contents: from the cache when it holds them, and otherwise from the
   * database, after which the cache is given them. Reads of one version that the cache does not
   * hold ask the database for it one at a time, so those that arrive together ask it once: the
   * others wait, and then find in the cache what the first one read.
   *
   * @param fromDatabase reads the contents from the database
   */
  Contents of(long catalogId, Supplier<Contents> fromDatabase) {
    if (cache == null) {
      return fromDatabase.get();
    }
    try {
      var kept = kept(catalogId);
      if (kept == null) {
        synchronized (oneAtATime[(int) (catalogId % oneAtATime.length)]) {
          kept = kept(catalogId);
          if (kept == null) {
            misses.increment();
            var contents = fromDatabase.get();
            keep(catalogId, contents);
            return contents;
          }
        }
      }
      hits.increment();
      return kept;
    } catch (Unanswered unanswered) {
      fallbacks.increment();
      return fromDatabase.get();
    }
  }

  /**
   * What the cache holds for the version, or null when it holds nothing for it. What it holds and
   * cannot be read counts as nothing, and is replaced by whoever reads the version next.
   *
   * @throws Unanswered when the cache does not answer, or is being left alone
   */
  private Contents kept(long catalogId) throws Unanswered {
    if (leftAlone()) {
      throw new Unanswered();
    }
    String kept;
    try {
      kept = cache.opsForValue().get(KEY + catalogId);
      answers();
    } catch (RuntimeException unanswered) {
      doesNotAnswer(unanswered);
      throw new Unanswered();
    }
    try {
      return kept == null ? null : JSON.readValue(kept, Contents.class);
    } catch (JacksonException unreadable) {
      return null;
    }
  }

  /** Gives the cache an Approved version's contents, which is left undone when it cannot be. */
  void keep(long catalogId, Contents contents) {
    if (cache == null || leftAlone()) {
      return;
    }
    try {
      cache.opsForValue().set(KEY + catalogId, JSON.writeValueAsString(contents), KEPT_FOR);
      answers();
    } catch (RuntimeException unanswered) {
      doesNotAnswer(unanswered);
    }
  }

  @Override
  public void clean() {
    if (cache == null) {
      return;
    }
    // ponytail: KEYS reads every key in one go, which holds Redis up for as long. There are a few
    // dozen. With thousands, SCAN reads them a batch at a time.
    var kept = cache.keys(IN_ANY_FORM + "*");
    if (!kept.isEmpty()) {
      cache.delete(kept);
    }
  }

  /** How many reads the cache answered, how many it had nothing for, and how many it failed. */
  Counts counts() {
    return new Counts(hits.sum(), misses.sum(), fallbacks.sum());
  }

  /** Asks the cache whether it is there, whether or not it is being left alone. */
  @Override
  public Health health() {
    if (cache == null) {
      return Health.unknown().build();
    }
    try {
      cache.execute((RedisCallback<String>) RedisConnection::ping);
      answers();
      return Health.up().build();
    } catch (RuntimeException unanswered) {
      doesNotAnswer(unanswered);
      return Health.down().build();
    }
  }

  private boolean leftAlone() {
    return down.get() && System.nanoTime() - leftAloneUntil < 0;
  }

  private void answers() {
    if (down.compareAndSet(true, false)) {
      log.info("The cache answers again.");
    }
  }

  /** The log says so once, when the cache stops answering, and not for each read after that. */
  private void doesNotAnswer(RuntimeException why) {
    leftAloneUntil = System.nanoTime() + LEFT_ALONE_FOR.toNanos();
    if (down.compareAndSet(false, true)) {
      log.warn(
          "The cache stopped answering, so Approved versions are read from the database: {}",
          why.toString());
    }
  }

  /** What an Approved version holds, without what places it among the catalogs. */
  record Contents(
      List<Trim> trims,
      List<Region> regions,
      List<Offering> offerings,
      List<FeatureRow> featureRows,
      List<Cell> cells,
      List<Rule> rules) {
    Contents {
      for (var held : new Object[] {trims, regions, offerings, featureRows, cells, rules}) {
        Objects.requireNonNull(held);
      }
    }
  }

  /** Reads of Approved versions' contents, by how the cache answered them. */
  record Counts(long hits, long misses, long fallbacks) {}

  /** Says that the cache did not answer. It is no error, and carries no trace of where it arose. */
  private static final class Unanswered extends Exception {
    Unanswered() {
      super(null, null, false, false);
    }
  }
}
