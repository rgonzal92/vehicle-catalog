package dev.rgonz.catalog.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.DemoAccounts.DemoAccount;
import dev.rgonz.catalog.user.SandboxSettings.SandboxAccount;
import dev.rgonz.catalog.user.UserAdministration.Account;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Checks which accounts are put back in their configured roles, and that an account the login
 * provider will not change does not stop the others.
 */
@ExtendWith(OutputCaptureExtension.class)
class SandboxRolesTest {
  private static final DemoAccounts DEMO =
      new DemoAccounts(
          List.of(new DemoAccount(Role.ADMIN, "demo-admin", "secret", "sub-demo-admin", "Admin")));
  private static final SandboxSettings SETTINGS =
      new SandboxSettings(
          List.of(
              new SandboxAccount("sub-first", "first", null, Role.AUTHOR),
              new SandboxAccount("sub-kept", "kept", null, Role.AUTHOR),
              new SandboxAccount("sub-second", "second", null, Role.MANAGER)),
          // A sandbox account that is also protected keeps whatever role it has.
          List.of("sub-kept"));

  private final InMemoryUserAdministration provider =
      new InMemoryUserAdministration(DEMO, SETTINGS);
  private final Sessions sessions = mock(Sessions.class);

  @Test
  void eachUnprotectedSandboxAccountGetsItsConfiguredRoleAndItsSessionsEnd() {
    for (var username : List.of("first", "kept", "second", "demo-admin")) {
      provider.setRole(username, Role.ADMIN);
    }
    provider.setRole("demo-admin", Role.AUTHOR);

    restoring(provider).restore();

    assertThat(provider.accounts())
        .extracting(Account::username, Account::role)
        .containsExactlyInAnyOrder(
            tuple("first", Role.AUTHOR),
            tuple("second", Role.MANAGER),
            tuple("kept", Role.ADMIN),
            tuple("demo-admin", Role.AUTHOR));
    verify(sessions).endOf("sub-first");
    verify(sessions).endOf("sub-second");
    verify(sessions, never()).endOf("sub-kept");
    verify(sessions, never()).endOf("sub-demo-admin");
  }

  @Test
  void anAccountTheProviderWillNotChangeIsLoggedAndTheOthersAreStillDone(CapturedOutput log) {
    provider.setRole("second", Role.ADMIN);
    var failingForTheFirst =
        new UserAdministration() {
          @Override
          public List<Account> accounts() {
            return provider.accounts();
          }

          @Override
          public Optional<Found> find(String username) {
            return provider.find(username);
          }

          @Override
          public void setRole(String username, Role role) {
            if (username.equals("first")) {
              throw new IllegalStateException("The login provider did not answer");
            }
            provider.setRole(username, role);
          }
        };

    restoring(failingForTheFirst).restore();

    assertThat(log.getOut())
        .contains("The sandbox account first was not put back in its configured role with its")
        .contains("The login provider did not answer");
    verify(sessions, never()).endOf("sub-first");
    assertThat(provider.accounts())
        .filteredOn(account -> account.username().equals("second"))
        .extracting(Account::role)
        .containsExactly(Role.MANAGER);
    verify(sessions).endOf("sub-second");
  }

  private SandboxRoles restoring(UserAdministration administration) {
    return new SandboxRoles(
        SETTINGS, new AccountClasses(DEMO, SETTINGS, provider), administration, sessions);
  }
}
