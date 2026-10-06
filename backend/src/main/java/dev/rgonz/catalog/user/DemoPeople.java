package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The people behind the demo accounts. Seeded catalogs belong to them, so each gets a local record
 * before they have ever signed in.
 */
@Repository
public class DemoPeople {
  private final DemoAccounts accounts;
  private final JdbcClient jdbc;

  DemoPeople(DemoAccounts accounts, JdbcClient jdbc) {
    this.accounts = accounts;
    this.jdbc = jdbc;
  }

  /**
   * The person behind the role's demo account. They are recorded under the account's subject, so
   * their first login finds the same record; someone who has signed in keeps their details.
   */
  public long idOf(Role role) {
    var account =
        accounts.demoAccounts().stream()
            .filter(candidate -> candidate.role() == role)
            .filter(candidate -> candidate.subject() != null && candidate.displayName() != null)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "The %s demo account needs a subject and a display name in app.demo-accounts"
                            .formatted(role.key())));

    return jdbc.sql(
            """
            INSERT INTO app_user (cognito_sub, username, display_name)
            VALUES (:subject, :username, :displayName)
            ON CONFLICT (cognito_sub) DO UPDATE SET cognito_sub = app_user.cognito_sub
            RETURNING id
            """)
        .param("subject", account.subject())
        .param("username", account.username())
        .param("displayName", account.displayName())
        .query(Long.class)
        .single();
  }
}
