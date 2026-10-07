package dev.rgonz.catalog.user;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Repository;

/** The local record of people who have signed in, keyed by their subject at the login provider. */
@Repository
public class AppUsers {
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

  /** The id of the signed-in person, which is what their catalogs name as their owner. */
  public long idOf(Authentication signedIn) {
    return signedIn(signedIn).id();
  }

  /** The signed-in person. A session whose person is not on record counts as no session. */
  AppUser signedIn(Authentication signedIn) {
    return findBySubject(signedIn.getName())
        .orElseThrow(
            () -> new AuthenticationCredentialsNotFoundException("No record of this person"));
  }

  /** When each person who has signed in last did so, by their subject. */
  Map<String, Instant> lastLogins() {
    return jdbc
        .sql("SELECT cognito_sub, last_login_at FROM app_user WHERE last_login_at IS NOT NULL")
        .query((row, number) -> Map.entry(row.getString(1), row.getTimestamp(2).toInstant()))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  Optional<AppUser> findBySubject(String subject) {
    return jdbc.sql("SELECT %s FROM app_user WHERE cognito_sub = :subject".formatted(COLUMNS))
        .param("subject", subject)
        .query(AppUser.class)
        .optional();
  }
}
