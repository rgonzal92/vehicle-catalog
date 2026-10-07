package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.UserAdministration.Account;
import dev.rgonz.catalog.user.UserAdministration.Found;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * What an admin may see of the people who use the app, and whose role an admin may change. An admin
 * signed in through a sandbox account is kept inside the sandbox: accounts outside it are not
 * listed and cannot be reached. A protected account's role is changed by no one.
 */
@Service
class AdminUsers {
  private final UserAdministration administration;
  private final AccountClasses classes;
  private final AppUsers users;
  private final FindByIndexNameSessionRepository<? extends Session> sessions;

  AdminUsers(
      UserAdministration administration,
      AccountClasses classes,
      AppUsers users,
      FindByIndexNameSessionRepository<? extends Session> sessions) {
    this.administration = administration;
    this.classes = classes;
    this.users = users;
    this.sessions = sessions;
  }

  /** The accounts the admin may see, by username. */
  List<ListedUser> list(Authentication admin) {
    var lastLogins = users.lastLogins();

    return administration.accounts().stream()
        .filter(account -> visibleTo(admin, account.subject()))
        .sorted(Comparator.comparing(Account::username))
        .map(account -> listed(account, lastLogins.get(account.subject())))
        .toList();
  }

  /**
   * Gives the account the role and ends its sessions, so that the person signs in again and holds
   * the role from then on. An account the admin may not see is not found, and that is settled
   * before anything is changed and before anything more is asked about the account. Giving an
   * account the role it has ends its sessions all the same, which is how a change whose second half
   * failed is finished.
   */
  ListedUser setRole(Authentication admin, String username, Role role) {
    Found account =
        administration
            .find(username)
            .filter(found -> visibleTo(admin, found.subject()))
            .orElseThrow(ApiException::notFound);
    if (classes.isProtected(account.subject())) {
      throw ApiException.forbidden(
          "PROTECTED_ACCOUNT", "The role of this account cannot be changed.");
    }

    // By the username the provider answered with, which is not always what it was asked for by.
    administration.setRole(account.username(), role);
    sessions.findByPrincipalName(account.subject()).keySet().forEach(sessions::deleteById);

    return listed(account.holding(role), users.lastLogins().get(account.subject()));
  }

  /**
   * Whether the admin may know of the account with that subject. A session names its person by
   * their subject, so that is what says through which kind of account the admin signed in.
   */
  private boolean visibleTo(Authentication admin, String subject) {
    return !classes.inSandbox(admin.getName()) || classes.inSandbox(subject);
  }

  private ListedUser listed(Account account, Instant lastLogin) {
    return new ListedUser(
        account.username(),
        account.email(),
        account.role(),
        lastLogin,
        !classes.isProtected(account.subject()));
  }

  /**
   * An account as an admin sees it.
   *
   * @param role the highest role the account holds, or null when it holds none
   * @param lastLogin when the person last signed in to the app, or null when they never have
   * @param changeable whether the account's role can be changed
   */
  record ListedUser(
      String username, String email, Role role, Instant lastLogin, boolean changeable) {}
}
