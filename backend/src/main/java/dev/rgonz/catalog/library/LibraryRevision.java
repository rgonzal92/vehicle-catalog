package dev.rgonz.catalog.library;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library's revision: one number that increases by one whenever the library changes in a way
 * that can break a catalog. That is when a feature is retired or made active again, a trim or a
 * region is deactivated or activated, or a global rule is added, changed, or deleted. A new name or
 * a new order breaks no catalog and leaves the revision where it is.
 */
@Component
public class LibraryRevision {
  private final JdbcClient jdbc;
  private final ApplicationEventPublisher events;

  LibraryRevision(JdbcClient jdbc, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.events = events;
  }

  /** The revision the library is at. */
  public long current() {
    return jdbc.sql("SELECT revision FROM library_state").query(Long.class).single();
  }

  /**
   * Counts a change, in the transaction of the change itself, and says that the library has
   * changed. Two changes at once are counted one after the other.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void increase() {
    var revision =
        jdbc.sql("UPDATE library_state SET revision = revision + 1 RETURNING revision")
            .query(Long.class)
            .single();
    events.publishEvent(new LibraryChanged(revision));
  }
}
