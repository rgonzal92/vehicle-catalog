package dev.rgonz.catalog.user;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * Starts a sign-in as Spring does, and has the login provider fill in the username when the request
 * names a demo account's, as the tour's sign-in step does. Any other name is left out, so a link
 * can only fill in a username that is published anyway.
 */
@Component
class LoginHints implements OAuth2AuthorizationRequestResolver {
  /** The parameter, both on the request to the app and on the one to the provider. */
  private static final String HINT = "login_hint";

  private final OAuth2AuthorizationRequestResolver requests;
  private final Set<String> usernames;

  LoginHints(ClientRegistrationRepository registrations, DemoAccounts accounts) {
    // The address SecurityConfig gives the sign-in endpoint.
    requests =
        new DefaultOAuth2AuthorizationRequestResolver(registrations, "/api/oauth2/authorization");
    usernames =
        accounts.demoAccounts().stream()
            .map(DemoAccounts.DemoAccount::username)
            .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    return hinted(request, requests.resolve(request));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
    return hinted(request, requests.resolve(request, registrationId));
  }

  private OAuth2AuthorizationRequest hinted(
      HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
    var hint = request.getParameter(HINT);
    if (resolved == null || hint == null || !usernames.contains(hint)) {
      return resolved;
    }
    return OAuth2AuthorizationRequest.from(resolved)
        .additionalParameters(parameters -> parameters.put(HINT, hint))
        .build();
  }
}
