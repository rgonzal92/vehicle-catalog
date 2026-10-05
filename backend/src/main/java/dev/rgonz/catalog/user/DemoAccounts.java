package dev.rgonz.catalog.user;

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

  /** What a visitor needs to sign in with one demo account. */
  record DemoAccount(String role, String username, String password) {}
}
