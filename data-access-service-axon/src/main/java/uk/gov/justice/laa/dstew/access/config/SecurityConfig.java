package uk.gov.justice.laa.dstew.access.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import uk.gov.justice.laa.dstew.access.ExcludeFromGeneratedCodeCoverage;
import uk.gov.justice.laa.dstew.access.shared.security.EffectiveAuthorizationProvider;
import uk.gov.laa.springboot.oauth2.EndpointAccessManager;
import uk.gov.laa.springboot.oauth2.Oauth2AccessDeniedHandler;
import uk.gov.laa.springboot.oauth2.Oauth2AuthenticationEntryPoint;

/**
 * Local security wrappers around the shared OAuth2 starter so Axon can keep starter-managed HTTP
 * security while preserving legacy method-security semantics and dev-token support.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(prefix = "feature", name = "disable-security", havingValue = "false")
@ExcludeFromGeneratedCodeCoverage
public class SecurityConfig {

  private static final String AUTHORITY_PREFIX = "APPROLE_";
  private static final List<String> DEV_TOKEN_ACCOUNTS = List.of("0Z123A", "1A987X");
  private static final String DEV_TOKEN_ENTRA_OID = "00000000-0000-0000-0000-000000000001";
  private static final String SECOND_DEV_TOKEN_ENTRA_OID = "00000000-0000-0000-0000-000000000002";
  private static final String PROVIDER_DEV_TOKEN_ENTRA_OID = "00000000-0000-0000-0000-000000000003";
  private static final List<String> CASEWORKER_ROLES =
      List.of("APPROLE_LAA_CASEWORKER", "ROLE_LAA_CASEWORKER");
  private static final Map<String, DevToken> DEV_TOKENS =
      Map.of(
          "swagger-caseworker-token",
          new DevToken(
              List.of("APPROLE_LAA_CASEWORKER", "ROLE_LAA_CASEWORKER"),
              "access_as_user",
              DEV_TOKEN_ENTRA_OID),
          "swagger-provider-token",
          new DevToken(
              List.of("APPROLE_LAA_CASEWORKER", "ROLE_LAA_CASEWORKER"),
              "access_as_provider",
              PROVIDER_DEV_TOKEN_ENTRA_OID),
          "swagger-caseworker-token-2",
          new DevToken(CASEWORKER_ROLES, "access_as_user", SECOND_DEV_TOKEN_ENTRA_OID),
          "unknown-token",
          new DevToken(List.of("APPROLE_UNKNOWN"), "", ""));

  @Value("${feature.enable-dev-token:false}")
  private boolean enableDevToken;

  /** Configures the application OAuth2 resource-server filter chain. */
  @Bean("oauth2SecurityFilterChain")
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain oauth2SecurityFilterChain(
      HttpSecurity httpSecurity,
      EndpointAccessManager endpointAccessManager,
      JwtAuthenticationConverter jwtConverter,
      ObjectProvider<AuthenticationManagerResolver<HttpServletRequest>>
          authenticationManagerResolver,
      @Qualifier("jacksonJsonMapper") ObjectMapper objectMapper,
      @Qualifier("xAuthorizationFilter") OncePerRequestFilter authorizationFilter)
      throws Exception {

    httpSecurity
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterAfter(authorizationFilter, BearerTokenAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers(endpointAccessManager.getUnprotectedUris()).permitAll();
              auth.anyRequest()
                  .access(
                      (authentication, context) ->
                          new AuthorizationDecision(
                              isAuthorized(
                                  authentication.get(),
                                  endpointAccessManager,
                                  context.getRequest())));
            })
        .exceptionHandling(
            exceptionHandling ->
                exceptionHandling
                    .authenticationEntryPoint(new Oauth2AuthenticationEntryPoint(objectMapper))
                    .accessDeniedHandler(new Oauth2AccessDeniedHandler(objectMapper)))
        .oauth2ResourceServer(
            resourceServerConfigurer(authenticationManagerResolver.getIfAvailable(), jwtConverter));

    return httpSecurity.build();
  }

  private Customizer<OAuth2ResourceServerConfigurer<HttpSecurity>> resourceServerConfigurer(
      AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver,
      JwtAuthenticationConverter jwtConverter) {
    return oauth2 -> {
      if (authenticationManagerResolver != null) {
        oauth2.authenticationManagerResolver(authenticationManagerResolver);
      } else {
        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter));
      }
    };
  }

  private boolean isAuthorized(
      Authentication authentication,
      EndpointAccessManager endpointAccessManager,
      HttpServletRequest request) {
    return authentication != null
        && authentication.isAuthenticated()
        && endpointAccessManager.isRequestAuthorized(authentication.getAuthorities(), request);
  }

  /**
   * Provides a JWT decoder with issuer and audience validation for non-dev bearer tokens.
   *
   * @return the configured JWT decoder
   */
  @Bean("fallbackJwtDecoder")
  @ConditionalOnMissingBean(JwtDecoder.class)
  public JwtDecoder fallbackJwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
      @Value("${spring.security.oauth2.resourceserver.jwt.audience}") String audience) {
    NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

    OAuth2TokenValidator<Jwt> audienceValidator =
        token -> {
          if (token.getAudience().contains(audience)) {
            return OAuth2TokenValidatorResult.success();
          }
          return OAuth2TokenValidatorResult.failure(
              new OAuth2Error("invalid_token", "The required audience is missing", null));
        };
    OAuth2TokenValidator<Jwt> withIssuer = JwtValidation.requiringExpiryWithIssuer(issuerUri);
    jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator));
    return jwtDecoder;
  }

  /**
   * Resolves authentication between the dev-token shortcut path and normal JWT validation.
   *
   * @param jwtDecoderProvider supplies the JWT decoder used for standard bearer tokens
   * @return the authentication manager resolver
   */
  @Bean
  AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver(
      ObjectProvider<JwtDecoder> jwtDecoderProvider,
      Environment environment,
      JwtAuthenticationConverter jwtConverter) {
    JwtDecoder resolvedJwtDecoder = jwtDecoderProvider.getIfAvailable();
    if (resolvedJwtDecoder == null) {
      resolvedJwtDecoder =
          fallbackJwtDecoder(
              environment.getRequiredProperty(
                  "spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
              environment.getRequiredProperty(
                  "spring.security.oauth2.resourceserver.jwt.issuer-uri"),
              environment.getRequiredProperty(
                  "spring.security.oauth2.resourceserver.jwt.audience"));
    }
    AuthenticationManager jwtAuthenticationManager =
        jwtAuthenticationManager(resolvedJwtDecoder, jwtConverter);
    AuthenticationManager devTokenAuthenticationManager =
        authentication -> authenticateDevToken(authentication, jwtConverter);

    return request ->
        isDevTokenRequest(request) ? devTokenAuthenticationManager : jwtAuthenticationManager;
  }

  /**
   * Effective authorization provider bean.
   *
   * @return the authorization provider
   */
  @Bean("entra")
  public EffectiveAuthorizationProvider authProvider() {
    return new SecurityContextEffectiveAuthorizationProvider();
  }

  private AuthenticationManager jwtAuthenticationManager(
      JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtConverter) {
    JwtAuthenticationProvider authenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
    authenticationProvider.setJwtAuthenticationConverter(
        jwt -> {
          var authentication = jwtConverter.convert(jwt);
          if (!StringUtils.hasText(jwt.getClaimAsString("scp"))) {
            return authentication;
          }
          // Existing delegated use cases still use the legacy caseworker method checks.
          Set<GrantedAuthority> authorities = new HashSet<>(authentication.getAuthorities());
          authorities.add(new SimpleGrantedAuthority("APPROLE_LAA_CASEWORKER"));
          authorities.add(new SimpleGrantedAuthority("ROLE_LAA_CASEWORKER"));
          return new JwtAuthenticationToken(jwt, authorities, authentication.getName());
        });
    return authenticationProvider::authenticate;
  }

  private Authentication authenticateDevToken(
      Authentication authentication, JwtAuthenticationConverter jwtConverter) {
    if (!(authentication instanceof BearerTokenAuthenticationToken bearerTokenAuthentication)) {
      return authentication;
    }

    DevToken devToken = DEV_TOKENS.get(bearerTokenAuthentication.getToken());
    if (devToken == null) {
      throw new InvalidBearerTokenException("Invalid bearer token");
    }

    Instant issuedAt = Instant.now();
    Jwt jwt =
        Jwt.withTokenValue(bearerTokenAuthentication.getToken())
            .header("alg", "none")
            .subject("dev-user")
            .claim("LAA_ACCOUNTS", DEV_TOKEN_ACCOUNTS)
            .claim("scp", devToken.scope())
            .claim("oid", devToken.entraOid())
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .build();
    Set<GrantedAuthority> authorities = new HashSet<>(jwtConverter.convert(jwt).getAuthorities());
    devToken.authorities().stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
    return new JwtAuthenticationToken(jwt, authorities);
  }

  private boolean isDevTokenRequest(HttpServletRequest request) {
    if (!enableDevToken) {
      return false;
    }

    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return false;
    }

    String token = authHeader.substring(7);
    return DEV_TOKENS.containsKey(token);
  }

  private record DevToken(List<String> authorities, String scope, String entraOid) {}

  /** Gives methods to check the SecurityContext for roles and username. */
  @ExcludeFromGeneratedCodeCoverage
  private class SecurityContextEffectiveAuthorizationProvider
      implements EffectiveAuthorizationProvider {
    @Override
    public boolean hasAppRole(String name) {
      Set<String> authorities = getAuthorities();
      return authorities.contains(AUTHORITY_PREFIX + name) || authorities.contains("ROLE_" + name);
    }

    @Override
    public boolean hasAnyAppRole(String... names) {
      return Arrays.stream(names).anyMatch(this::hasAppRole);
    }

    @Override
    public boolean hasName() {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      return auth != null && auth.isAuthenticated() && !auth.getName().isBlank();
    }

    private Set<String> getAuthorities() {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      return (auth != null && auth.isAuthenticated())
          ? auth.getAuthorities().stream()
              .map(GrantedAuthority::getAuthority)
              .collect(Collectors.toUnmodifiableSet())
          : Set.of();
    }
  }
}
