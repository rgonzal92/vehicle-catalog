package dev.rgonz.catalog.user;

import dev.rgonz.catalog.user.DemoAccounts.DemoAccount;
import dev.rgonz.catalog.user.SandboxSettings.SandboxAccount;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Says of an account, by its subject at the login provider, whether it is a sandbox account and
 * whether it is a protected account. Both come from configuration alone, so nothing a caller sends
 * can change either.
 *
 * <p>An admin whose subject is not listed as a sandbox account sees and changes everyone, so a
 * wrong subject in the configuration would open the sandbox without a sign. The application
 * therefore does not start unless every configured account is, at the login provider, the account
 * the configuration says it is.
 */
@Component
class AccountClasses {
  private final Set<String> sandbox = new HashSet<>();
  private final Set<String> protectedAccounts = new HashSet<>();

  AccountClasses(DemoAccounts demo, SandboxSettings settings, UserAdministration administration) {
    // Every demo account is both: visitors sign in through it, and its role stays what it is.
    for (DemoAccount account : demo.demoAccounts()) {
      requireTheSameAccount(administration, account.username(), account.subject());
      if (account.subject() != null) {
        sandbox.add(account.subject());
        protectedAccounts.add(account.subject());
      }
    }
    for (SandboxAccount account : settings.sandboxAccounts()) {
      // Its subject says which account it is, and its username and role are what the demo reset
      // puts it back to.
      if (account.subject() == null || account.username() == null || account.role() == null) {
        throw new IllegalStateException(
            "The sandbox account %s needs a subject, a username, and a role in app.sandbox-accounts"
                .formatted(account.username()));
      }
      requireTheSameAccount(administration, account.username(), account.subject());
      sandbox.add(account.subject());
    }
    protectedAccounts.addAll(settings.protectedAccounts());
  }

  /** Whether visitors to the public demo may see and manage the account. */
  boolean inSandbox(String subject) {
    return sandbox.contains(subject);
  }

  /** Whether the account's role cannot be changed from the app. */
  boolean isProtected(String subject) {
    return protectedAccounts.contains(subject);
  }

  /**
   * Refuses to go on unless the provider's account of that username has that subject. An account
   * configured without a subject has to be one the provider does not have: one it has would count
   * as neither a sandbox account nor a protected one.
   */
  private static void requireTheSameAccount(
      UserAdministration administration, String username, String subject) {
    if (username == null) {
      return;
    }
    var atProvider = administration.find(username).map(UserAdministration.Found::subject);
    var same = subject == null ? atProvider.isEmpty() : atProvider.equals(Optional.of(subject));
    if (!same) {
      throw new IllegalStateException(
          "The configured account %s is not the login provider's account of that name: check its"
                  .formatted(username)
              + " subject in app.demo-accounts or app.sandbox-accounts");
    }
  }
}
