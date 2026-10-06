package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Stands in for the login provider's administration where there is no Amazon Cognito: local runs
 * and tests. It holds the configured sandbox accounts, demo accounts included, and forgets every
 * change when the application stops.
 *
 * <p>A role changed here shows in the list and ends the account's sessions, but the local login
 * server knows nothing of it and goes on issuing the groups it is configured with.
 */
@Component
@ConditionalOnExpression("@environment.getProperty('app.cognito.user-pool-id', '').isBlank()")
class InMemoryUserAdministration implements UserAdministration {
  private static final Logger log = LoggerFactory.getLogger(InMemoryUserAdministration.class);

  private final DemoAccounts demo;
  private final SandboxSettings settings;
  private final Map<String, Account> byUsername = new ConcurrentHashMap<>();

  InMemoryUserAdministration(DemoAccounts demo, SandboxSettings settings) {
    this.demo = demo;
    this.settings = settings;
    refill();
    log.info(
        "No user pool is configured, so the configured accounts stand in for one and a role"
            + " changed here reaches no login provider");
  }

  @Override
  public List<Account> accounts() {
    return List.copyOf(byUsername.values());
  }

  /** By username or, as Amazon Cognito also does, by email. */
  @Override
  public Optional<Found> find(String username) {
    return byUsername.values().stream()
        .filter(account -> account.username().equals(username) || username.equals(account.email()))
        .findFirst()
        .map(account -> new Found(account.subject(), account.username(), account.email()));
  }

  @Override
  public void setRole(String username, Role role) {
    var changed =
        byUsername.computeIfPresent(
            username,
            (name, account) ->
                new Account(account.subject(), account.username(), account.email(), role));
    if (changed == null) {
      throw new IllegalArgumentException("There is no account " + username);
    }
  }

  /** Holds the account from now on, in place of one with the same username. */
  void add(Account account) {
    byUsername.put(account.username(), account);
  }

  /** Goes back to the configured accounts, each with its configured role. */
  void refill() {
    byUsername.clear();
    for (var account : demo.demoAccounts()) {
      if (account.subject() != null && account.username() != null) {
        add(new Account(account.subject(), account.username(), null, account.role()));
      }
    }
    for (var account : settings.sandboxAccounts()) {
      add(new Account(account.subject(), account.username(), account.email(), account.role()));
    }
  }
}
