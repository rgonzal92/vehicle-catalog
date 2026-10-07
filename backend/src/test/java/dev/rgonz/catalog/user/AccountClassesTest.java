package dev.rgonz.catalog.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.DemoAccounts.DemoAccount;
import dev.rgonz.catalog.user.SandboxSettings.SandboxAccount;
import dev.rgonz.catalog.user.UserAdministration.Account;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks which accounts count as sandbox accounts and as protected ones, and that a configuration
 * that disagrees with the login provider about who an account is stops the start.
 */
class AccountClassesTest {
  private static final DemoAccounts DEMO =
      new DemoAccounts(
          List.of(new DemoAccount(Role.ADMIN, "demo-admin", "secret", "sub-demo-admin", "Admin")));
  private static final SandboxSettings SETTINGS =
      new SandboxSettings(
          List.of(new SandboxAccount("sub-visitor", "visitor", "v@example.test", Role.AUTHOR)),
          List.of("sub-operator"));

  /** A provider that has the configured accounts as configured, and one account more. */
  private final InMemoryUserAdministration provider =
      new InMemoryUserAdministration(DEMO, SETTINGS);

  @Test
  void demoAccountsAreBothSandboxAccountsAreNotProtectedAndTheOperatorIsOutside() {
    var classes = new AccountClasses(DEMO, SETTINGS, provider);

    assertThat(classes.inSandbox("sub-demo-admin")).isTrue();
    assertThat(classes.isProtected("sub-demo-admin")).isTrue();
    assertThat(classes.inSandbox("sub-visitor")).isTrue();
    assertThat(classes.isProtected("sub-visitor")).isFalse();
    assertThat(classes.inSandbox("sub-operator")).isFalse();
    assertThat(classes.isProtected("sub-operator")).isTrue();
    assertThat(classes.inSandbox("sub-anyone-else")).isFalse();
    assertThat(classes.isProtected("sub-anyone-else")).isFalse();
  }

  @Test
  void aConfiguredSubjectThatIsNotTheProvidersStopsTheStart() {
    provider.add(new Account("sub-of-a-later-demo-admin", "demo-admin", null, Role.ADMIN));

    assertThatThrownBy(() -> new AccountClasses(DEMO, SETTINGS, provider))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("demo-admin");
  }

  @Test
  void aConfiguredAccountTheProviderDoesNotHaveStopsTheStart() {
    var withOneMore =
        new SandboxSettings(
            List.of(new SandboxAccount("sub-ghost", "ghost", null, Role.AUTHOR)), List.of());

    assertThatThrownBy(() -> new AccountClasses(DEMO, withOneMore, provider))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ghost");
  }

  @Test
  void aDemoAccountWithoutASubjectStopsTheStartWhereTheProviderHasThatAccount() {
    var unnamed =
        new DemoAccounts(List.of(new DemoAccount(Role.ADMIN, "demo-admin", "secret", null, null)));

    assertThatThrownBy(() -> new AccountClasses(unnamed, SETTINGS, provider))
        .as("it would be neither a sandbox account nor protected")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("demo-admin");
  }

  @Test
  void aDemoAccountWithoutASubjectIsLeftOutWhereTheProviderDoesNotHaveIt() {
    var unnamed =
        new DemoAccounts(List.of(new DemoAccount(Role.ADMIN, "demo-admin", "secret", null, null)));
    var providerWithoutIt = new InMemoryUserAdministration(unnamed, SETTINGS);

    var classes = new AccountClasses(unnamed, SETTINGS, providerWithoutIt);

    assertThat(classes.inSandbox("sub-visitor")).isTrue();
  }

  @Test
  void aSandboxAccountWithoutASubjectStopsTheStart() {
    var unnamed =
        new SandboxSettings(
            List.of(new SandboxAccount(null, "visitor", null, Role.AUTHOR)), List.of());

    assertThatThrownBy(() -> new AccountClasses(DEMO, unnamed, provider))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("visitor");
  }
}
