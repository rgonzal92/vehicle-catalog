package dev.rgonz.catalog.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What people are told in the app: left for a person, read by them, and marked as read. */
@Component
public class Notifications {
  /** The most notifications a person is shown at once: the newest. */
  static final int MOST_SHOWN = 20;

  private final JdbcClient jdbc;
  private final JsonMapper json;

  Notifications(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Leaves a notification for a person, unless they have been told of the same event already.
   *
   * @param kind what kind of thing happened, which decides how the app words it
   * @param eventKey what identifies the event, so that telling of it twice tells of it once
   * @param payload what the app needs to say it in words
   */
  public void tell(long userId, String kind, String eventKey, Map<String, ?> payload) {
    jdbc.sql(
            """
            INSERT INTO notification (user_id, kind, event_key, payload)
            VALUES (:user, :kind, :key, :payload::jsonb)
            ON CONFLICT (event_key) DO NOTHING
            """)
        .param("user", userId)
        .param("kind", kind)
        .param("key", eventKey)
        .param("payload", json.writeValueAsString(payload))
        .update();
  }

  /** A person's newest notifications, newest first, and how many they have not read in all. */
  @Transactional(readOnly = true)
  Inbox inbox(long userId) {
    var items =
        jdbc.sql(
                """
                SELECT id, kind, payload::text AS payload, created_at, read_at IS NOT NULL AS read
                FROM notification
                WHERE user_id = :user
                ORDER BY id DESC
                LIMIT :most
                """)
            .param("user", userId)
            .param("most", MOST_SHOWN)
            .query(
                (row, _) ->
                    new Notification(
                        row.getLong("id"),
                        row.getString("kind"),
                        json.readTree(row.getString("payload")),
                        row.getTimestamp("created_at").toInstant(),
                        row.getBoolean("read")))
            .list();

    return new Inbox(unread(userId), items);
  }

  /**
   * Marks a person's notifications as read, up to and including the one named: what they were shown
   * and nothing that arrived since. Answers with how many they have not read afterwards.
   */
  @Transactional
  long markRead(long userId, long upTo) {
    jdbc.sql(
            """
            UPDATE notification
            SET read_at = now()
            WHERE user_id = :user AND id <= :upTo AND read_at IS NULL
            """)
        .param("user", userId)
        .param("upTo", upTo)
        .update();

    return unread(userId);
  }

  private long unread(long userId) {
    return jdbc.sql("SELECT count(*) FROM notification WHERE user_id = :user AND read_at IS NULL")
        .param("user", userId)
        .query(Long.class)
        .single();
  }

  /** A person's notifications as the app shows them. */
  record Inbox(long unread, List<Notification> items) {}

  /**
   * One notification.
   *
   * @param kind what kind of thing happened, which decides how the app words it
   * @param payload what the app needs to say it in words, and where it leads
   */
  record Notification(long id, String kind, JsonNode payload, Instant createdAt, boolean read) {}
}
