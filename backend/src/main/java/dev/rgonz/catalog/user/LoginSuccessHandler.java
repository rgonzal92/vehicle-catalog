package dev.rgonz.catalog.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Finishes a login: records the person locally, replaces the session's login details with a copy
 * that holds no tokens, and sends the browser to the dashboard.
 */
@Component
class LoginSuccessHandler implements AuthenticationSuccessHandler {
  private final AppUsers users;
  private final SecurityContextRepository contexts;
  private final URI publicUrl;

  LoginSuccessHandler(
      AppUsers users,
      SecurityContextRepository contexts,
      @Value("${app.public-url}") URI publicUrl) {
    this.users = users;
    this.contexts = contexts;
    this.publicUrl = publicUrl;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    var login = (OAuth2AuthenticationToken) authentication;
    var person = (OidcUser) login.getPrincipal();
    users.recordLogin(
        person.getSubject(), username(person), person.getEmail(), displayName(person));

    // The provider's tokens ride along in the principal and its authorities; keep neither.
    var authorities =
        login.getAuthorities().stream()
            .map(authority -> new SimpleGrantedAuthority(authority.getAuthority()))
            .toList();
    var principal = new DefaultOAuth2User(authorities, Map.of("sub", person.getSubject()), "sub");
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new OAuth2AuthenticationToken(
            principal, authorities, login.getAuthorizedClientRegistrationId()));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);

    response.sendRedirect(publicUrl + "/dashboard");
  }

  /** Amazon Cognito sends the username in its own claim; other providers use the standard one. */
  private static String username(OidcUser person) {
    var cognito = person.getClaimAsString("cognito:username");
    if (cognito != null) {
      return cognito;
    }
    return person.getPreferredUsername() != null
        ? person.getPreferredUsername()
        : person.getSubject();
  }

  private static String displayName(OidcUser person) {
    return person.getFullName() != null ? person.getFullName() : username(person);
  }
}
