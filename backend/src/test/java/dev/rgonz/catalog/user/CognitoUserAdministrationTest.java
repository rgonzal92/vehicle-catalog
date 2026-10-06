package dev.rgonz.catalog.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.UserAdministration.Account;
import dev.rgonz.catalog.user.UserAdministration.Found;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRemoveUserFromGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/**
 * Checks what is asked of Amazon Cognito and what is made of its answers, against a stand-in for
 * its client that answers only the exact requests each test expects. Nothing here reaches Amazon
 * Cognito.
 */
class CognitoUserAdministrationTest {
  private static final String POOL = "us-east-1_example";

  private final CognitoIdentityProviderClient cognito = mock(CognitoIdentityProviderClient.class);
  private final CognitoUserAdministration administration =
      new CognitoUserAdministration(cognito, POOL);

  @Test
  void everyAccountIsListedWithTheHighestRoleItHolds() {
    when(cognito.listUsers(usersFrom(null)))
        .thenReturn(
            ListUsersResponse.builder()
                .users(user("ada", "sub-ada", "ada@example.test"))
                .paginationToken("more")
                .build());
    when(cognito.listUsers(usersFrom("more")))
        .thenReturn(
            ListUsersResponse.builder()
                .users(user("ben", "sub-ben", "ben@example.test"), user("cy", "sub-cy", null))
                .build());
    when(cognito.listUsersInGroup(membersOf("admin", null)))
        .thenReturn(members(null, user("ada", "sub-ada", null)));
    when(cognito.listUsersInGroup(membersOf("manager", null))).thenReturn(members(null));
    // The authors come on two pages, and the second one is where ben is.
    when(cognito.listUsersInGroup(membersOf("author", null)))
        .thenReturn(members("more-authors", user("ada", "sub-ada", null)));
    when(cognito.listUsersInGroup(membersOf("author", "more-authors")))
        .thenReturn(members(null, user("ben", "sub-ben", null)));

    assertThat(administration.accounts())
        .containsExactly(
            new Account("sub-ada", "ada", "ada@example.test", Role.ADMIN),
            new Account("sub-ben", "ben", "ben@example.test", Role.AUTHOR),
            new Account("sub-cy", "cy", null, null));
  }

  @Test
  void anAccountIsFoundAsThePoolNamesItWithoutAskingAnythingMoreAboutIt() {
    when(cognito.adminGetUser(
            AdminGetUserRequest.builder().userPoolId(POOL).username("ben@example.test").build()))
        .thenReturn(
            AdminGetUserResponse.builder()
                .username("ben")
                .userAttributes(attribute("sub", "sub-ben"), attribute("email", "ben@example.test"))
                .build());

    assertThat(administration.find("ben@example.test"))
        .contains(new Found("sub-ben", "ben", "ben@example.test"));
    verify(cognito).adminGetUser(any(AdminGetUserRequest.class));
    verifyNoMoreInteractions(cognito);
  }

  @Test
  void anAccountThePoolDoesNotHaveIsNotFound() {
    when(cognito.adminGetUser(any(AdminGetUserRequest.class)))
        .thenThrow(UserNotFoundException.builder().message("User does not exist.").build());

    assertThat(administration.find("nobody")).isEmpty();
  }

  @Test
  void aNameThePoolCannotTakeForOneFindsNoAccount() {
    when(cognito.adminGetUser(any(AdminGetUserRequest.class)))
        .thenThrow(InvalidParameterException.builder().message("Invalid username.").build());

    assertThat(administration.find("not a name")).isEmpty();
  }

  @Test
  void aNewRoleIsJoinedBeforeTheOthersAreLeft() {
    // The account's groups come on two pages, and the second one is where admin is.
    when(cognito.adminListGroupsForUser(groupsOf("ben", null)))
        .thenReturn(groups("more-groups", "author", "a-group-the-app-does-not-know"));
    when(cognito.adminListGroupsForUser(groupsOf("ben", "more-groups")))
        .thenReturn(groups(null, "admin"));

    administration.setRole("ben", Role.MANAGER);

    var calls = inOrder(cognito);
    calls
        .verify(cognito)
        .adminAddUserToGroup(
            AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL)
                .username("ben")
                .groupName("manager")
                .build());
    calls.verify(cognito).adminRemoveUserFromGroup(leaving("admin"));
    calls.verify(cognito).adminRemoveUserFromGroup(leaving("author"));
    verify(cognito, never()).adminRemoveUserFromGroup(leaving("a-group-the-app-does-not-know"));
  }

  @Test
  void aRoleTheAccountAlreadyHoldsIsNeitherJoinedAgainNorLeft() {
    when(cognito.adminListGroupsForUser(groupsOf("ben", null))).thenReturn(groups(null, "manager"));

    administration.setRole("ben", Role.MANAGER);

    verify(cognito, never()).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
    verify(cognito, never()).adminRemoveUserFromGroup(any(AdminRemoveUserFromGroupRequest.class));
  }

  @Test
  void aFailureOfThePoolIsAnsweredAsOneAndNotInThePoolsWords() {
    when(cognito.listUsersInGroup(any(ListUsersInGroupRequest.class)))
        .thenThrow(ResourceNotFoundException.builder().message("Group not found.").build());
    when(cognito.adminGetUser(any(AdminGetUserRequest.class)))
        .thenThrow(ResourceNotFoundException.builder().message("User pool not found.").build());
    when(cognito.adminListGroupsForUser(any(AdminListGroupsForUserRequest.class)))
        .thenThrow(ResourceNotFoundException.builder().message("User pool not found.").build());

    for (Runnable asked :
        List.<Runnable>of(
            administration::accounts,
            () -> administration.find("ben"),
            () -> administration.setRole("ben", Role.AUTHOR))) {
      assertThatThrownBy(asked::run)
          .isInstanceOfSatisfying(
              ApiException.class,
              refusal -> {
                assertThat(refusal.getStatusCode().value()).isEqualTo(503);
                assertThat(refusal.getBody().getProperties())
                    .containsEntry("code", "LOGIN_PROVIDER_UNAVAILABLE");
                assertThat(refusal.getBody().getDetail()).doesNotContain("not found");
              });
    }
  }

  private static ListUsersRequest usersFrom(String token) {
    return ListUsersRequest.builder().userPoolId(POOL).paginationToken(token).build();
  }

  private static ListUsersInGroupRequest membersOf(String group, String token) {
    return ListUsersInGroupRequest.builder()
        .userPoolId(POOL)
        .groupName(group)
        .nextToken(token)
        .build();
  }

  private static ListUsersInGroupResponse members(String next, UserType... users) {
    return ListUsersInGroupResponse.builder().users(users).nextToken(next).build();
  }

  private static AdminListGroupsForUserRequest groupsOf(String username, String token) {
    return AdminListGroupsForUserRequest.builder()
        .userPoolId(POOL)
        .username(username)
        .nextToken(token)
        .build();
  }

  private static AdminListGroupsForUserResponse groups(String next, String... names) {
    return AdminListGroupsForUserResponse.builder()
        .groups(
            List.of(names).stream()
                .map(name -> GroupType.builder().groupName(name).build())
                .toList())
        .nextToken(next)
        .build();
  }

  private static AdminRemoveUserFromGroupRequest leaving(String group) {
    return AdminRemoveUserFromGroupRequest.builder()
        .userPoolId(POOL)
        .username("ben")
        .groupName(group)
        .build();
  }

  private static UserType user(String username, String subject, String email) {
    var attributes = new ArrayList<AttributeType>();
    attributes.add(attribute("sub", subject));
    if (email != null) {
      attributes.add(attribute("email", email));
    }
    return UserType.builder().username(username).attributes(attributes).build();
  }

  private static AttributeType attribute(String name, String value) {
    return AttributeType.builder().name(name).value(value).build();
  }
}
