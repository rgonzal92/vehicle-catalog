package dev.rgonz.catalog.user;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The local record of people who have signed in, keyed by their subject at the login provider. */
@Repository
class AppUsers {
  private static final String COLUMNS =
      "id, cognito_sub AS subject, username, email, display_name AS displayName";

  private final JdbcClient jdbc;

  AppUsers(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the person at their first login and refreshes their details at every later one. */
  AppUser recordLogin(String subject, String username, String email, String displayName) {
    return jdbc.sql(
            """
            INSERT INTO app_user (cognito_sub, username, email, display_name, last_login_at)
            VALUES (:subject, :username, :email, :displayName, clock_timestamp())
            ON CONFLICT (cognito_sub) DO UPDATE
                SET username = EXCLUDED.username,
                    email = EXCLUDED.email,
                    display_name = EXCLUDED.display_name,
                    last_login_at = EXCLUDED.last_login_at
            RETURNING %s
            """
                .formatted(COLUMNS))
        .param("subject", subject)
        .param("username", username)
        .param("email", email)
        .param("displayName", displayName)
        .query(AppUser.class)
        .single();
  }

  Optional<AppUser> findBySubject(String subject) {
    return jdbc.sql("SELECT %s FROM app_user WHERE cognito_sub = :subject".formatted(COLUMNS))
        .param("subject", subject)
        .query(AppUser.class)
        .optional();
  }
}
