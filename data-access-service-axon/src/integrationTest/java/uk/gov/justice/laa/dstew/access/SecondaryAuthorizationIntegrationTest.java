package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import uk.gov.justice.laa.dstew.access.security.AllowApiSecondaryAuthorizationTest;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "feature.disable-security=false",
      "feature.enable-dev-token=false",
      "spring.main.allow-bean-definition-overriding=true"
    })
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SecondaryAuthorizationIntegrationTest.TestControllerConfiguration.class)
class SecondaryAuthorizationIntegrationTest {

  private static final String ISSUER_ID = "entra";
  private static final int MOCK_OAUTH_PORT = 9999;
  private static final String AUDIENCE = "laa-data-access-api";

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static GenericContainer<?> mockOauth2Server =
      new GenericContainer<>(DockerImageName.parse("ghcr.io/navikt/mock-oauth2-server:6.0.2"))
          .withExposedPorts(MOCK_OAUTH_PORT)
          .withEnv("SERVER_PORT", String.valueOf(MOCK_OAUTH_PORT))
          .withEnv("JSON_CONFIG_PATH", "/etc/mock-oauth2/config.json")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("mock-oauth2/secondary-authorization-config.json"),
              "/etc/mock-oauth2/config.json")
          .waitingFor(
              Wait.forHttp("/entra/.well-known/openid-configuration")
                  .forStatusCode(HttpStatus.OK.value()));

  @DynamicPropertySource
  static void configureResourceServer(DynamicPropertyRegistry registry) {
    registry.add("ENTRA_ISSUER_URI", SecondaryAuthorizationIntegrationTest::issuerUrl);
    registry.add("ENTRA_JWK_SET_URI", () -> issuerUrl() + "/jwks");
    registry.add("ENTRA_AUD", () -> AUDIENCE);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUrl());
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> issuerUrl() + "/jwks");
    registry.add("spring.security.oauth2.resourceserver.jwt.audience", () -> AUDIENCE);
  }

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private RestTemplateBuilder restTemplateBuilder;

  @Test
  void givenSignedAutoGrantTokenWithoutScopes_whenCallingMappedOperations_thenReachesApplication() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.setBearerAuth(clientCredentialsToken("auto-grant-client"));
    String path = "http://localhost:" + port + "/api/v0/applications/" + UUID.randomUUID();

    var read = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(read.getStatusCode())
        .as("response: %s", read.getBody())
        .isEqualTo(HttpStatus.NOT_FOUND);

    var write =
        restTemplate.exchange(
            path + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("outcome", "MANUAL"), headers),
            String.class);
    assertThat(write.getStatusCode())
        .as("response: %s", write.getBody())
        .isEqualTo(HttpStatus.NOT_FOUND);

    var unrelated =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/work-list",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
    assertThat(unrelated.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void
      givenSignedServiceTokenWithoutAutoGrantRole_whenCallingMappedOperations_thenReturnsForbidden() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.setBearerAuth(clientCredentialsToken("other-service-client"));
    String path = "http://localhost:" + port + "/api/v0/applications/" + UUID.randomUUID();

    assertThat(
            restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            restTemplate
                .exchange(
                    path + "/auto-grant-outcome",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("outcome", "MANUAL"), headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void givenRoleFreePrimaryToken_whenCallingSecondaryAuthorizationEndpoint_thenReturnsForbidden() {
    var response =
        restTemplate.exchange(
            secondaryAuthorizationEndpoint(),
            HttpMethod.GET,
            new HttpEntity<>(primaryAuthorizationHeaders()),
            String.class);

    assertThat(response.getStatusCode())
        .as("response body: %s", response.getBody())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void
      givenRoleFreePrimaryTokenAndSecondaryTokenWithTestRole_whenCallingSecondaryAuthorizationEndpoint_thenReturnsOk() {
    HttpHeaders headers = primaryAuthorizationHeaders();
    headers.set("X-Authorization", clientCredentialsToken("secondary-client"));

    var response =
        restTemplate.exchange(
            secondaryAuthorizationEndpoint(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode())
        .as("response body: %s", response.getBody())
        .isEqualTo(HttpStatus.OK);
  }

  private HttpHeaders primaryAuthorizationHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.setBearerAuth(clientCredentialsToken("primary-client"));
    return headers;
  }

  private String secondaryAuthorizationEndpoint() {
    return "http://localhost:" + port + "/api/test/secondary-authorization";
  }

  private String clientCredentialsToken(String clientId) {
    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("grant_type", "client_credentials");
    body.add("client_id", clientId);
    body.add("client_secret", "unused-in-mock");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    Map<String, Object> response =
        restTemplateBuilder
            .build()
            .postForObject(issuerUrl() + "/token", new HttpEntity<>(body, headers), Map.class);

    assertThat(response).isNotNull();
    assertThat(response).containsKey("access_token");
    return (String) response.get("access_token");
  }

  private static String issuerUrl() {
    return "http://"
        + mockOauth2Server.getHost()
        + ":"
        + mockOauth2Server.getMappedPort(MOCK_OAUTH_PORT)
        + "/"
        + ISSUER_ID;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestControllerConfiguration {

    @Bean
    SecondaryAuthorizationTestController secondaryAuthorizationTestController() {
      return new SecondaryAuthorizationTestController();
    }
  }

  @RestController
  @RequestMapping("/api/test/secondary-authorization")
  static class SecondaryAuthorizationTestController {

    @GetMapping
    @AllowApiSecondaryAuthorizationTest
    String get() {
      return "secondary authorization granted";
    }
  }
}
