package dev.rgonz.catalog.user;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.UserAdministration.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks who an admin sees and whose role an admin may change. The configured sandbox accounts are
 * the two demo accounts and "visitor"; "operator" is protected and outside the sandbox, and "rhea"
 * is a person outside it whom nothing protects.
 */
class AdminUsersIT extends ApplicationIT {
  private static final String VISITOR = "sandbox-visitor";
  private static final String OPERATOR = "operator";
  private static final String RHEA = "sub-rhea";

  @Autowired InMemoryUserAdministration accounts;
  @Autowired AppUsers users;
  @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

  @BeforeEach
  void theAccountsAsConfiguredAndTwoPeopleOutsideTheSandbox() {
    jdbc.sql("DELETE FROM spring_session WHERE principal_name IN (?, ?, ?, 'author')")
        .params(VISITOR, OPERATOR, RHEA)
        .update();
    accounts.refill();
    accounts.add(new Account(OPERATOR, "the-operator", "operator@example.test", Role.ADMIN));
    accounts.add(new Account(RHEA, "rhea", "rhea@example.test", Role.AUTHOR));
  }

  @Test
  void anAdminOutsideTheSandboxSeesEveryAccount() {
    users.recordLogin(RHEA, "rhea", "rhea@example.test", "Rhea");

    var list = mvc.get().uri("/api/admin/users").with(outsideAdmin());

    assertThat(list)
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$[*].username")
        .asArray()
        .containsExactly("demo-author", "demo-manager", "rhea", "the-operator", "visitor");
    assertThat(list).bodyJson().extractingPath("$[2].email").isEqualTo("rhea@example.test");
    assertThat(list).bodyJson().extractingPath("$[2].role").isEqualTo("author");
    assertThat(list).bodyJson().extractingPath("$[2].lastLogin").asString().isNotBlank();
    assertThat(list)
        .bodyJson()
        .extractingPath("$[*].changeable")
        .asArray()
        .as("the demo accounts and the operator are protected")
        .containsExactly(false, false, true, false, true);
  }

  @Test
  void anAccountNoOneHasSignedInToHasNoLastLoginAndOneWithoutARoleHasNone() {
    accounts.add(new Account("sub-newcomer", "newcomer", "newcomer@example.test", null));

    var list = mvc.get().uri("/api/admin/users").with(outsideAdmin());

    assertThat(list).bodyJson().extractingPath("$[2].username").isEqualTo("newcomer");
    assertThat(list).bodyJson().extractingPath("$[2].lastLogin").isNull();
    assertThat(list).bodyJson().extractingPath("$[2].role").isNull();
    assertThat(list).bodyJson().extractingPath("$[2].changeable").isEqualTo(true);
  }

  @Test
  void aSandboxAdminSeesOnlySandboxAccounts() {
    var list = mvc.get().uri("/api/admin/users").with(sandboxAdmin());

    assertThat(list)
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$[*].username")
        .asArray()
        .containsExactly("demo-author", "demo-manager", "visitor");
    assertThat(list)
        .bodyJson()
        .extractingPath("$[*].changeable")
        .asArray()
        .containsExactly(false, false, true);
  }

  @Test
  void aSandboxAdminCannotReachAnAccountOutsideTheSandbox() {
    signIn(RHEA);
    signIn(OPERATOR);

    for (var username : new String[] {"rhea", "the-operator"}) {
      assertThat(setRole(sandboxAdmin(), username, "manager"))
          .as(username)
          .hasStatus(404)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NOT_FOUND");
    }

    assertThat(roleOf("rhea")).isEqualTo(Role.AUTHOR);
    assertThat(roleOf("the-operator")).isEqualTo(Role.ADMIN);
    assertThat(sessions.findByPrincipalName(RHEA)).as("no session is ended").hasSize(1);
    assertThat(sessions.findByPrincipalName(OPERATOR)).hasSize(1);
  }

  @Test
  void changingAnUnprotectedSandboxAccountsRoleEndsItsSessions() {
    signIn(VISITOR);
    signIn(VISITOR);
    signIn(RHEA);

    var changed = setRole(sandboxAdmin(), "visitor", "manager");

    assertThat(changed).hasStatusOk().bodyJson().extractingPath("$.role").isEqualTo("manager");
    assertThat(changed).bodyJson().extractingPath("$.username").isEqualTo("visitor");
    assertThat(roleOf("visitor")).isEqualTo(Role.MANAGER);
    assertThat(sessions.findByPrincipalName(VISITOR)).as("every session of theirs").isEmpty();
    assertThat(sessions.findByPrincipalName(RHEA)).as("and no one else's").hasSize(1);
    assertThat(mvc.get().uri("/api/admin/users").with(sandboxAdmin()))
        .bodyJson()
        .extractingPath("$[2].role")
        .isEqualTo("manager");
  }

  @Test
  void anAdminOutsideTheSandboxChangesAnyoneWhoIsNotProtected() {
    signIn(RHEA);

    assertThat(setRole(outsideAdmin(), "rhea", "admin")).hasStatusOk();
    assertThat(setRole(outsideAdmin(), "visitor", "manager")).hasStatusOk();

    assertThat(roleOf("rhea")).isEqualTo(Role.ADMIN);
    assertThat(roleOf("visitor")).isEqualTo(Role.MANAGER);
    assertThat(sessions.findByPrincipalName(RHEA)).isEmpty();
  }

  @Test
  void aProtectedAccountsRoleCannotBeChangedByAnyAdmin() {
    signIn("author");

    for (var admin : new RequestPostProcessor[] {outsideAdmin(), sandboxAdmin()}) {
      assertThat(setRole(admin, "demo-author", "admin"))
          .hasStatus(403)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("PROTECTED_ACCOUNT");
    }
    assertThat(setRole(outsideAdmin(), "the-operator", "author"))
        .hasStatus(403)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("PROTECTED_ACCOUNT");

    assertThat(roleOf("demo-author")).isEqualTo(Role.AUTHOR);
    assertThat(roleOf("the-operator")).isEqualTo(Role.ADMIN);
    assertThat(sessions.findByPrincipalName("author")).as("no session is ended").hasSize(1);
  }

  @Test
  void authorsAndManagersAreRefusedBoth() {
    for (var role : new Role[] {Role.AUTHOR, Role.MANAGER}) {
      assertThat(mvc.get().uri("/api/admin/users").with(signedInAs(role, VISITOR)))
          .as(role.name())
          .hasStatus(403);
      assertThat(mvc.head().uri("/api/admin/users").with(signedInAs(role, VISITOR)))
          .as("%s asking for the list's headers alone", role.name())
          .hasStatus(403);
      assertThat(setRole(signedInAs(role, VISITOR), "visitor", "admin"))
          .as(role.name())
          .hasStatus(403)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("FORBIDDEN");
    }
    assertThat(roleOf("visitor")).isEqualTo(Role.AUTHOR);
  }

  @Test
  void anAccountThatDoesNotExistIsNotFound() {
    assertThat(setRole(outsideAdmin(), "nobody", "author")).hasStatus(404);
  }

  @Test
  void anAccountFoundByItsEmailIsChangedUnderItsUsername() {
    assertThat(setRole(sandboxAdmin(), "visitor@example.test", "manager"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.username")
        .isEqualTo("visitor");
    assertThat(roleOf("visitor")).isEqualTo(Role.MANAGER);

    assertThat(setRole(sandboxAdmin(), "rhea@example.test", "manager"))
        .as("outside the sandbox, by whatever it is asked for")
        .hasStatus(404);
    assertThat(roleOf("rhea")).isEqualTo(Role.AUTHOR);
  }

  @Test
  void aRoleThatDoesNotExistIsRefusedAndChangesNothing() {
    signIn(VISITOR);

    assertThat(setRole(outsideAdmin(), "visitor", "owner")).hasStatus(400);
    assertThat(setRole(outsideAdmin(), "visitor", " manager"))
        .as("not quite a role")
        .hasStatus(400);
    assertThat(
            mvc.put()
                .uri("/api/admin/users/visitor/role")
                .with(outsideAdmin())
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": 0}"))
        .as("a role is its name, not its place among the roles")
        .hasStatus(400);
    assertThat(
            mvc.put()
                .uri("/api/admin/users/visitor/role")
                .with(outsideAdmin())
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .hasStatus(422);

    assertThat(roleOf("visitor")).isEqualTo(Role.AUTHOR);
    assertThat(sessions.findByPrincipalName(VISITOR)).hasSize(1);
  }

  /** An admin signed in through an account that is not a sandbox account. */
  private RequestPostProcessor outsideAdmin() {
    return signedInAs(Role.ADMIN, OPERATOR);
  }

  /** An admin signed in through a sandbox account. */
  private RequestPostProcessor sandboxAdmin() {
    return signedInAs(Role.ADMIN, VISITOR);
  }

  private MvcTestResult setRole(RequestPostProcessor admin, String username, String role) {
    return mvc.put()
        .uri("/api/admin/users/{username}/role", username)
        .with(admin)
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"role\": \"%s\"}".formatted(role))
        .exchange();
  }

  /** The role the account holds at the stand-in for the login provider. */
  private Role roleOf(String username) {
    return accounts.accounts().stream()
        .filter(account -> account.username().equals(username))
        .findFirst()
        .orElseThrow()
        .role();
  }

  /** Leaves the person with one more session, as a login does. */
  private <S extends Session> void signIn(String subject) {
    @SuppressWarnings("unchecked")
    var repository = (FindByIndexNameSessionRepository<S>) sessions;
    var session = repository.createSession();
    session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, subject);
    repository.save(session);
  }
}
