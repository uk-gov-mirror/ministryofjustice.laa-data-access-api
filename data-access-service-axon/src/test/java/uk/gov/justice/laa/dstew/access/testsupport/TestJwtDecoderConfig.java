package uk.gov.justice.laa.dstew.access.testsupport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import uk.gov.laa.springboot.oauth2.testsupport.StubJwtDecoder;
import uk.gov.laa.springboot.oauth2.testsupport.StubJwtToken;

/**
 * Shared starter-backed JWT support for Spring Boot tests.
 *
 * <p>Provides a deterministic bearer token that the real starter security filter chain can decode
 * without a mock OAuth2 server.
 */
@TestConfiguration
public class TestJwtDecoderConfig {

  public static final String BEARER_TOKEN = "test-caseworker-token";
  public static final String OTHER_BEARER_TOKEN = "other-test-caseworker-token";
  public static final String OFFICE_A_BEARER_TOKEN = "office-a-caseworker-token";
  public static final String OFFICE_A_AND_B_BEARER_TOKEN = "office-a-and-b-caseworker-token";
  public static final String OFFICE_C_BEARER_TOKEN = "office-c-caseworker-token";
  public static final String NO_ACCOUNTS_BEARER_TOKEN = "no-accounts-caseworker-token";
  public static final String UNSCOPED_BEARER_TOKEN = "unscoped-caseworker-token";
  public static final String AUTO_GRANT_BEARER_TOKEN = "test-auto-grant-service-token";
  public static final String OTHER_SERVICE_BEARER_TOKEN = "test-other-service-token";
  public static final String DELEGATED_WITHOUT_ROLE_BEARER_TOKEN = "test-obo-without-role-token";
  public static final UUID AUTO_GRANT_SERVICE_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");
  public static final String ACCESS_AS_USER = "access_as_user";
  public static final String ACCESS_AS_PROVIDER = "access_as_provider";
  public static final List<String> TEST_LAA_ACCOUNTS = List.of("1A001B", "2B002C");
  public static final UUID CASEWORKER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  public static final UUID OTHER_CASEWORKER_ID =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  public static final String ISSUER_URI = "https://issuer.example.test";
  public static final String AUDIENCE = "api://data-access-api-test";

  @Bean
  @Primary
  JwtDecoder jwtDecoder() {
    return StubJwtDecoder.of(
        serviceToken(AUTO_GRANT_BEARER_TOKEN, "LAA_DECIDE_AUTO_GRANT"),
        serviceToken(OTHER_SERVICE_BEARER_TOKEN, "OTHER_SERVICE"),
        new StubJwtToken(
            DELEGATED_WITHOUT_ROLE_BEARER_TOKEN,
            "delegated-user@example.com",
            null,
            null,
            Map.of(
                "iss",
                ISSUER_URI,
                "aud",
                List.of(AUDIENCE),
                "oid",
                CASEWORKER_ID.toString(),
                "scp",
                ACCESS_AS_USER)),
        userToken(BEARER_TOKEN, "caseworker@example.com", CASEWORKER_ID),
        userToken(OTHER_BEARER_TOKEN, "other-caseworker@example.com", OTHER_CASEWORKER_ID),
        token(
            UNSCOPED_BEARER_TOKEN,
            "unscoped-caseworker@example.com",
            CASEWORKER_ID,
            null,
            Map.of()),
        providerToken(OFFICE_A_BEARER_TOKEN, List.of(TEST_LAA_ACCOUNTS.getFirst())),
        providerToken(OFFICE_A_AND_B_BEARER_TOKEN, TEST_LAA_ACCOUNTS),
        providerToken(OFFICE_C_BEARER_TOKEN, List.of("3C003D")),
        providerToken(NO_ACCOUNTS_BEARER_TOKEN, List.of()));
  }

  private static StubJwtToken serviceToken(String token, String role) {
    return new StubJwtToken(
        token,
        "auto-grant-service",
        new String[] {role},
        null,
        Map.of(
            "iss",
            ISSUER_URI,
            "aud",
            List.of(AUDIENCE),
            "roles",
            List.of(role),
            "idtyp",
            "app",
            "azp",
            "44444444-4444-4444-4444-444444444444",
            "oid",
            AUTO_GRANT_SERVICE_ID.toString()));
  }

  private static StubJwtToken userToken(String token, String subject, UUID oid) {
    return token(token, subject, oid, ACCESS_AS_USER, Map.of());
  }

  private static StubJwtToken providerToken(String token, List<String> accounts) {
    return token(
        token,
        "accounts-provider@example.com",
        CASEWORKER_ID,
        ACCESS_AS_PROVIDER,
        Map.of("LAA_ACCOUNTS", accounts));
  }

  private static StubJwtToken token(
      String token, String subject, UUID oid, String scope, Map<String, Object> additionalClaims) {
    Map<String, Object> claims = new HashMap<>(additionalClaims);
    claims.put("iss", ISSUER_URI);
    claims.put("aud", List.of(AUDIENCE));
    claims.put("oid", oid.toString());
    if (scope != null) {
      claims.put("scp", scope);
    }
    return new StubJwtToken(token, subject, new String[] {"LAA_CASEWORKER"}, null, claims);
  }
}
