package dev.rgonz.catalog.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared logins published on the landing page, one per role. Their passwords are public on
 * purpose.
 */
@ConfigurationProperties("app")
record DemoAccounts(List<DemoAccount> demoAccounts) {
  DemoAccounts {
    demoAccounts = demoAccounts == null ? List.of() : List.copyOf(demoAccounts);
  }

  /**
   * What a visitor needs to sign in with one demo account, which is published, and who the account
   * is to the app, which is not: its subject at the login provider and the name shown for it.
   */
  record DemoAccount(
      Role role,
      String username,
      String password,
      @JsonIgnore String subject,
      @JsonIgnore String displayName) {}
}
