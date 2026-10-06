package dev.rgonz.catalog.user;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.Role;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRemoveUserFromGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/**
 * The accounts of the Amazon Cognito user pool the app signs people in with. A role is a group of
 * the pool named after it, and an account's role is the highest one whose group it is in. The app
 * reaches the pool as whatever AWS identity it runs under.
 *
 * <p>When the pool does not answer, or refuses, the request is answered 503 and the pool's own
 * words are logged, not sent.
 */
@Component
@ConditionalOnExpression("!@environment.getProperty('app.cognito.user-pool-id', '').isBlank()")
class CognitoUserAdministration implements UserAdministration {
  private static final Logger log = LoggerFactory.getLogger(CognitoUserAdministration.class);

  private final CognitoIdentityProviderClient cognito;
  private final String userPool;

  /** A pool's id starts with the region it is in, which is where it is reached. */
  @Autowired
  CognitoUserAdministration(@Value("${app.cognito.user-pool-id}") String userPool) {
    this(
        CognitoIdentityProviderClient.builder()
            .region(Region.of(userPool.substring(0, userPool.indexOf('_'))))
            .build(),
        userPool);
  }

  CognitoUserAdministration(CognitoIdentityProviderClient cognito, String userPool) {
    this.cognito = cognito;
    this.userPool = userPool;
  }

  @PreDestroy
  void close() {
    cognito.close();
  }

  @Override
  public List<Account> accounts() {
    return asking(
        () -> {
          // Lowest role first, so that the highest role an account holds is the one that stays.
          var roles = new HashMap<String, Role>();
          for (var role : List.of(Role.values()).reversed()) {
            for (var user : usersIn(role)) {
              roles.put(user.username(), role);
            }
          }

          return users().stream()
              .map(
                  user ->
                      new Account(
                          attribute(user.attributes(), "sub"),
                          user.username(),
                          attribute(user.attributes(), "email"),
                          roles.get(user.username())))
              .toList();
        });
  }

  /** A name the pool cannot take for one, such as one with a space in it, finds no account. */
  @Override
  public Optional<Found> find(String username) {
    return asking(
        () -> {
          try {
            var user =
                cognito.adminGetUser(
                    AdminGetUserRequest.builder().userPoolId(userPool).username(username).build());

            return Optional.of(
                new Found(
                    attribute(user.userAttributes(), "sub"),
                    user.username(),
                    attribute(user.userAttributes(), "email")));
          } catch (UserNotFoundException | InvalidParameterException noSuchAccount) {
            return Optional.empty();
          }
        });
  }

  /** Joins the role's group before leaving the others, so the account is never without a role. */
  @Override
  public void setRole(String username, Role role) {
    asking(
        () -> {
          var groups = groupsOf(username);
          if (!groups.contains(role.key())) {
            cognito.adminAddUserToGroup(
                AdminAddUserToGroupRequest.builder()
                    .userPoolId(userPool)
                    .username(username)
                    .groupName(role.key())
                    .build());
          }
          for (var other : Role.values()) {
            if (other != role && groups.contains(other.key())) {
              cognito.adminRemoveUserFromGroup(
                  AdminRemoveUserFromGroupRequest.builder()
                      .userPoolId(userPool)
                      .username(username)
                      .groupName(other.key())
                      .build());
            }
          }
          return null;
        });
  }

  private List<UserType> users() {
    var users = new ArrayList<UserType>();
    String next = null;
    do {
      var page =
          cognito.listUsers(
              ListUsersRequest.builder().userPoolId(userPool).paginationToken(next).build());
      users.addAll(page.users());
      next = page.paginationToken();
    } while (next != null);

    return users;
  }

  private List<UserType> usersIn(Role role) {
    var users = new ArrayList<UserType>();
    String next = null;
    do {
      var page =
          cognito.listUsersInGroup(
              ListUsersInGroupRequest.builder()
                  .userPoolId(userPool)
                  .groupName(role.key())
                  .nextToken(next)
                  .build());
      users.addAll(page.users());
      next = page.nextToken();
    } while (next != null);

    return users;
  }

  private Set<String> groupsOf(String username) {
    var groups = new HashSet<String>();
    String next = null;
    do {
      var page =
          cognito.adminListGroupsForUser(
              AdminListGroupsForUserRequest.builder()
                  .userPoolId(userPool)
                  .username(username)
                  .nextToken(next)
                  .build());
      page.groups().stream().map(GroupType::groupName).forEach(groups::add);
      next = page.nextToken();
    } while (next != null);

    return groups;
  }

  /** Asks the pool, and answers a failure of the pool's with a refusal the caller can show. */
  private static <T> T asking(Supplier<T> question) {
    try {
      return question.get();
    } catch (SdkException failure) {
      log.warn("The user pool did not do what was asked of it", failure);
      throw ApiException.unavailable(
          "LOGIN_PROVIDER_UNAVAILABLE",
          "The login provider did not answer. Nothing may have changed; look at the list and try"
              + " again.");
    }
  }

  private static String attribute(List<AttributeType> attributes, String name) {
    return attributes.stream()
        .filter(attribute -> attribute.name().equals(name))
        .map(AttributeType::value)
        .findFirst()
        .orElse(null);
  }
}
