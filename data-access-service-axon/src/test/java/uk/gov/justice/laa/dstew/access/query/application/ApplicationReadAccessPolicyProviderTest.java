package uk.gov.justice.laa.dstew.access.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uk.gov.justice.laa.dstew.access.query.utils.security.OfficeCodeReadAccessScope;
import uk.gov.justice.laa.dstew.access.query.utils.security.UnrestrictedReadAccessScope;
import uk.gov.justice.laa.dstew.access.security.SecurityHelper;

class ApplicationReadAccessPolicyProviderTest {

  private final ApplicationReadAccessPolicyProvider policy =
      new ApplicationReadAccessPolicyProvider();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void givenUserScope_whenResolved_thenAccessIsUnrestricted() {
    authenticate("access_as_user", List.of());

    assertThat(policy.resolve()).isInstanceOf(UnrestrictedReadAccessScope.class);
  }

  @Test
  void givenProviderScopeAndAccounts_whenResolved_thenReturnsDistinctTrimmedOfficeCodes() {
    authenticate("access_as_provider", List.of(" A ", "B", "A", " "));

    OfficeCodeReadAccessScope scope = (OfficeCodeReadAccessScope) policy.resolve();

    assertThat(scope.permittedOfficeCodes()).containsExactlyInAnyOrder("A", "B");
  }

  @Test
  void givenProviderScopeWithoutAccounts_whenResolved_thenReturnsEmptyOfficeScope() {
    authenticate("access_as_provider", List.of());

    OfficeCodeReadAccessScope scope = (OfficeCodeReadAccessScope) policy.resolve();

    assertThat(scope.permittedOfficeCodes()).isEmpty();
  }

  @Test
  void givenProviderScopeWithNullAccount_whenResolved_thenNullAccountIsIgnored() {
    try (MockedStatic<SecurityHelper> securityHelper = Mockito.mockStatic(SecurityHelper.class)) {
      securityHelper.when(SecurityHelper::getScopes).thenReturn(Set.of("access_as_provider"));
      securityHelper
          .when(SecurityHelper::getLaaAccounts)
          .thenReturn(Arrays.asList(null, " A ", " "));

      OfficeCodeReadAccessScope scope = (OfficeCodeReadAccessScope) policy.resolve();

      assertThat(scope.permittedOfficeCodes()).containsExactly("A");
    }
  }

  @Test
  void givenBothSupportedScopes_whenResolved_thenProviderScopeTakesPrecedence() {
    authenticate("access_as_user access_as_provider", List.of("A"));

    assertThat(policy.resolve()).isEqualTo(new OfficeCodeReadAccessScope(java.util.Set.of("A")));
  }

  @Test
  void givenNoSupportedScope_whenResolved_thenThrowsAccessDeniedException() {
    authenticate("unrelated_scope", List.of());

    assertThatExceptionOfType(AccessDeniedException.class)
        .isThrownBy(policy::resolve)
        .withMessage("Token does not contain a supported application access scope");
  }

  @Test
  void givenAutoGrantServiceWithoutScopes_whenResolved_thenAccessIsUnrestricted() {
    Jwt jwt =
        Jwt.withTokenValue("service-token")
            .header("alg", "none")
            .claim("roles", List.of(SecurityHelper.DECIDE_AUTO_GRANT_ROLE))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

    assertThat(policy.resolve()).isInstanceOf(UnrestrictedReadAccessScope.class);
  }

  @Test
  void givenOtherServiceWithoutScopes_whenResolved_thenDeniesAccess() {
    Jwt jwt =
        Jwt.withTokenValue("service-token")
            .header("alg", "none")
            .claim("roles", List.of("OTHER_SERVICE"))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

    assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(policy::resolve);
  }

  @Test
  void givenProviderWithAutoGrantRole_whenResolved_thenRetainsOfficeRestriction() {
    Jwt jwt =
        Jwt.withTokenValue("provider-token")
            .header("alg", "none")
            .claim("scp", "access_as_provider")
            .claim("roles", List.of(SecurityHelper.DECIDE_AUTO_GRANT_ROLE))
            .claim("LAA_ACCOUNTS", List.of("1A001B"))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

    assertThat(policy.resolve()).isEqualTo(new OfficeCodeReadAccessScope(Set.of("1A001B")));
  }

  @Test
  void givenAutoGrantRoleWithUnsupportedScope_whenResolved_thenDeniesAccess() {
    Jwt jwt =
        Jwt.withTokenValue("delegated-token")
            .header("alg", "none")
            .claim("scp", "unrelated_scope")
            .claim("roles", List.of(SecurityHelper.DECIDE_AUTO_GRANT_ROLE))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

    assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(policy::resolve);
  }

  private static void authenticate(String scopes, List<String> accounts) {
    Jwt jwt =
        new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("LAA_ACCOUNTS", accounts, "scp", scopes));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_CASEWORKER"))));
  }
}
