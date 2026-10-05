package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.DemoAccounts.DemoAccount;
import java.util.List;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the frontend who is signed in and which demo accounts a visitor can use. */
@RestController
class UserController {
  private final AppUsers users;
  private final DemoAccounts demoAccounts;
  private final RoleHierarchy roles;

  UserController(AppUsers users, DemoAccounts demoAccounts, RoleHierarchy roles) {
    this.users = users;
    this.demoAccounts = demoAccounts;
    this.roles = roles;
  }

  @GetMapping("/api/me")
  Me me(Authentication authentication) {
    var person =
        users
            .findBySubject(authentication.getName())
            .orElseThrow(
                () -> new AuthenticationCredentialsNotFoundException("No record of this person"));
    return new Me(
        person.id(),
        person.displayName(),
        person.email(),
        Role.heldBy(authentication.getAuthorities(), roles));
  }

  @GetMapping("/api/demo-accounts")
  List<DemoAccount> demoAccounts() {
    return demoAccounts.demoAccounts();
  }

  /** The signed-in person as the frontend sees them, with every role they hold, highest first. */
  record Me(long id, String name, String email, List<Role> roles) {}
}
