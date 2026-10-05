package dev.rgonz.catalog.core;

import java.util.Locale;

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
  public String label() {
    return name().toLowerCase(Locale.ROOT);
  }
}
