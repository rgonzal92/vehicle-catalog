package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.AdminUsers.ListedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets admins list the people who use the app and change their roles. */
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController {
  private final AdminUsers adminUsers;

  AdminUserController(AdminUsers adminUsers) {
    this.adminUsers = adminUsers;
  }

  @GetMapping
  @RequiresRole(Role.ADMIN)
  List<ListedUser> list(Authentication admin) {
    return adminUsers.list(admin);
  }

  @PutMapping("/{username}/role")
  @RequiresRole(Role.ADMIN)
  ListedUser setRole(
      Authentication admin, @PathVariable String username, @Valid @RequestBody NewRole given) {
    return adminUsers.setRole(admin, username, given.role());
  }

  /** The role an account is to have. */
  record NewRole(@NotNull(message = "Say which role the account is to have.") Role role) {}
}
