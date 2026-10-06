package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.Role;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which accounts, beyond the demo accounts, visitors to the public demo may see and manage, and
 * which, beyond the demo accounts, no one may change from the app. Both are named by their subject
 * at the login provider.
 */
@ConfigurationProperties("app")
record SandboxSettings(List<SandboxAccount> sandboxAccounts, List<String> protectedAccounts) {
  SandboxSettings {
    sandboxAccounts = sandboxAccounts == null ? List.of() : List.copyOf(sandboxAccounts);
    protectedAccounts = protectedAccounts == null ? List.of() : List.copyOf(protectedAccounts);
  }

  /** A sandbox account that is not a demo account, with the role it is configured to have. */
  record SandboxAccount(String subject, String username, String email, Role role) {}
}
