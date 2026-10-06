package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.DemoAccounts.DemoAccount;
import java.util.Optional;
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
   * Records the person behind the role's demo account, unless they are on record already, and
   * answers with their id. They are recorded under the account's subject, so their first login
   * finds the same record; someone who has signed in keeps their details. An account that does not
   * say who it is, with a subject, a username, and a display name, is not recorded.
   */
  public Optional<Long> record(Role role) {
    return accounts.demoAccounts().stream()
        .filter(account -> account.role() == role)
        .filter(
            account ->
                account.subject() != null
                    && account.username() != null
                    && account.displayName() != null)
        .findFirst()
        .map(this::record);
  }

  private long record(DemoAccount account) {
    return jdbc.sql(
            """
            INSERT INTO app_user (cognito_sub, username, display_name)
            VALUES (:subject, :username, :displayName)
            -- Changes nothing; it is here so that a person already on record answers with their id.
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
