package dev.rgonz.catalog.user;

import dev.rgonz.catalog.user.DemoAccounts.DemoAccount;
import java.util.List;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the frontend who is signed in and which demo accounts a visitor can use. */
@RestController
class UserController {
  private static final String ROLE_PREFIX = "ROLE_";

  private final AppUsers users;
  private final DemoAccounts demoAccounts;

  UserController(AppUsers users, DemoAccounts demoAccounts) {
    this.users = users;
    this.demoAccounts = demoAccounts;
  }

  @GetMapping("/api/me")
  Me me(Authentication authentication) {
    var person =
        users
            .findBySubject(authentication.getName())
            .orElseThrow(
                () -> new AuthenticationCredentialsNotFoundException("No record of this person"));
    var roles =
        authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .filter(authority -> authority.startsWith(ROLE_PREFIX))
            .map(authority -> authority.substring(ROLE_PREFIX.length()).toLowerCase())
            .toList();

    return new Me(person.id(), person.displayName(), person.email(), roles);
  }

  @GetMapping("/api/demo-accounts")
  List<DemoAccount> demoAccounts() {
    return demoAccounts.demoAccounts();
  }

  /** The signed-in person as the frontend sees them. */
  record Me(long id, String name, String email, List<String> roles) {}
}
