package dev.rgonz.catalog.core;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;

/** What a person may do, highest first. Each role includes the ones after it. */
public enum Role {
  ADMIN,
  MANAGER,
  AUTHOR;

  /** The authority Spring Security checks for this role. */
  public String authority() {
    return "ROLE_" + name();
  }

  /** The role's name in the API, which is also the name of its group at the login provider. */
  @JsonValue
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Every role these authorities give, directly or through a higher role, highest first. */
  public static List<Role> heldBy(
      Collection<? extends GrantedAuthority> authorities, RoleHierarchy hierarchy) {
    var reachable =
        hierarchy.getReachableGrantedAuthorities(authorities).stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

    return Arrays.stream(values()).filter(role -> reachable.contains(role.authority())).toList();
  }
}
