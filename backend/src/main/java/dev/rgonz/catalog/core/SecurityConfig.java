package dev.rgonz.catalog.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sign-in through the login provider, a server-side session, CSRF protection for a single-page app,
 * and API errors as problem details. Everything under /api needs a session except health, the demo
 * account list, and the sign-in endpoints themselves.
 */
@Configuration
class SecurityConfig {
  /** The name the login provider is registered under; it appears in the sign-in addresses. */
  private static final String PROVIDER = "cognito";

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  /** Admin includes manager, and manager includes author. */
  @Bean
  static RoleHierarchy roleHierarchy() {
    var roles = Role.values();
    var hierarchy = RoleHierarchyImpl.withDefaultRolePrefix();
    for (int higher = 0; higher < roles.length - 1; higher++) {
      hierarchy.role(roles[higher].name()).implies(roles[higher + 1].name());
    }
    return hierarchy.build();
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SecurityContextRepository contexts,
      AuthenticationSuccessHandler signedIn,
      ClientRegistrationRepository registrations,
      ProblemWriter problems,
      @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlers,
      JsonMapper json,
      @Value("${app.public-url}") URI publicUrl,
      @Value("${app.logout-url}") String logoutUrl) {
    var landing = publicUrl + "/";

    http.authorizeHttpRequests(
        requests -> {
          requests
              .requestMatchers("/api/health/**", "/api/demo-accounts")
              .permitAll()
              .requestMatchers("/api/me")
              .authenticated();
          requireHigherRoles(requests, handlers);
          requests.anyRequest().hasRole(Role.AUTHOR.name());
        });

    http.securityContext(context -> context.securityContextRepository(contexts));

    // The sign-in endpoints live under /api, so one route prefix reaches the whole backend.
    http.oauth2Login(
        login ->
            login
                .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/oauth2/authorization"))
                .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/login/oauth2/code/*"))
                .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(rolesFromGroups()))
                .authorizedClientRepository(new DiscardedTokens())
                .successHandler(signedIn)
                .failureHandler(new SimpleUrlAuthenticationFailureHandler(landing)));

    // Angular reads the token cookie and echoes it in the X-XSRF-TOKEN header on writes.
    var csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
    http.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens));

    var providerLogout = providerLogoutAddress(registrations, logoutUrl, landing);
    http.logout(
        logout ->
            logout.logoutUrl("/api/logout").logoutSuccessHandler(signedOut(json, providerLogout)));

    // An API call without a session gets a 401, not a redirect to the login provider.
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (request, response, exception) ->
                        problems.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"))
                .accessDeniedHandler(
                    (request, response, exception) ->
                        problems.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN")));

    http.requestCache(AbstractHttpConfigurer::disable);

    return http.build();
  }

  /**
   * Registers every endpoint marked with {@link RequiresRole}. Checking here, before the request is
   * read, means a person without the role is refused even when what they sent is invalid.
   */
  private static void requireHigherRoles(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
          requests,
      RequestMappingHandlerMapping handlers) {
    handlers
        .getHandlerMethods()
        .forEach(
            (mapping, handler) -> {
              var required = handler.getMethodAnnotation(RequiresRole.class);
              if (required == null) {
                return;
              }
              var paths = mapping.getPathPatternsCondition().getPatternValues();
              for (var method : mapping.getMethodsCondition().getMethods()) {
                requests
                    .requestMatchers(method.asHttpMethod(), paths.toArray(String[]::new))
                    .hasRole(required.value().name());
              }
            });
  }

  /** Turns the login provider's groups into roles. A group the app does not know grants nothing. */
  private static GrantedAuthoritiesMapper rolesFromGroups() {
    return authorities -> {
      var mapped = new HashSet<GrantedAuthority>(authorities);
      for (var authority : authorities) {
        if (authority instanceof OidcUserAuthority login) {
          var groups = login.getIdToken().getClaimAsStringList("cognito:groups");
          for (var role : Role.values()) {
            if (groups != null && groups.contains(role.key())) {
              mapped.add(new SimpleGrantedAuthority(role.authority()));
            }
          }
        }
      }
      return mapped;
    };
  }

  /**
   * Where the browser goes after sign-out: the provider's logout address with the two parameters
   * Amazon Cognito requires, which then returns to the landing page.
   */
  private static String providerLogoutAddress(
      ClientRegistrationRepository registrations, String logoutUrl, String landing) {
    return UriComponentsBuilder.fromUriString(logoutUrl)
        .queryParam("client_id", registrations.findByRegistrationId(PROVIDER).getClientId())
        .queryParam("logout_uri", landing)
        .toUriString();
  }

  /**
   * The session is already ended when this runs. The single-page app made the request, so it gets
   * the address to navigate to instead of a redirect it could not follow across origins.
   */
  private static LogoutSuccessHandler signedOut(JsonMapper json, String providerLogout) {
    return (request, response, authentication) -> {
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      json.writeValue(response.getOutputStream(), Map.of("logoutUrl", providerLogout));
    };
  }

  /** The app never calls the provider on a person's behalf, so their tokens are not kept. */
  private static final class DiscardedTokens implements OAuth2AuthorizedClientRepository {
    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
        String registrationId, Authentication principal, HttpServletRequest request) {
      return null;
    }

    @Override
    public void saveAuthorizedClient(
        OAuth2AuthorizedClient client,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {}

    @Override
    public void removeAuthorizedClient(
        String registrationId,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {}
  }
}
