package dev.rgonz.catalog.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puts the sandbox accounts whose roles can be changed back in the roles they are configured with.
 * These are the accounts an admin of the public demo may change, so this undoes what visitors did
 * to them.
 */
@Component
public class SandboxRoles {
  private static final Logger log = LoggerFactory.getLogger(SandboxRoles.class);

  private final SandboxSettings settings;
  private final AccountClasses classes;
  private final UserAdministration administration;
  private final Sessions sessions;

  SandboxRoles(
      SandboxSettings settings,
      AccountClasses classes,
      UserAdministration administration,
      Sessions sessions) {
    this.settings = settings;
    this.classes = classes;
    this.administration = administration;
    this.sessions = sessions;
  }

  /**
   * Gives each such account its configured role and ends its sessions. An account for which either
   * fails is logged and left, and the others are still done.
   */
  public void restore() {
    for (var account : settings.sandboxAccounts()) {
      if (classes.isProtected(account.subject())) {
        continue;
      }
      try {
        administration.setRole(account.username(), account.role());
        sessions.endOf(account.subject());
      } catch (RuntimeException failure) {
        log.warn(
            "The sandbox account {} was not put back in its configured role with its sessions"
                + " ended",
            account.username(),
            failure);
      }
    }
  }
}
