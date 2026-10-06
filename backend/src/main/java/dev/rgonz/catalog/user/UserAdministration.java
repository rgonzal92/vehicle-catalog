package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Optional;

/**
 * The accounts at the login provider and the role each holds there. Who may see or change which
 * account is decided by the caller of this, not here.
 */
interface UserAdministration {
  /** Every account, with the role it holds. */
  List<Account> accounts();

  /**
   * The account the provider finds for the name, if it finds one. A provider may find an account by
   * more than its username, by its email for one, so the account says what its username is. Its
   * role is not looked up: whether the caller may know of the account is settled first.
   */
  Optional<Found> find(String username);

  /** Gives the account the role in place of any role it had. */
  void setRole(String username, Role role);

  /**
   * An account at the login provider.
   *
   * @param subject what the provider calls the account in a login token, which never changes
   * @param role the highest role the account holds, or null when it holds none
   */
  record Account(String subject, String username, String email, Role role) {}

  /** An account as {@link #find} answers with it: who it is, without the role it holds. */
  record Found(String subject, String username, String email) {
    Account holding(Role role) {
      return new Account(subject, username, email, role);
    }
  }
}
