package dev.rgonz.catalog.notification;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** What people are told in the app. */
@Component
public class Notifications {
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
}
