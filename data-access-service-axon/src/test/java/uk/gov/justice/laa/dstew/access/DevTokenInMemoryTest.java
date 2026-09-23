package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import uk.gov.justice.laa.dstew.access.testsupport.TestJwtDecoderConfig;

@SpringBootTest(
    classes = DataAccessServiceAxonApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.default_schema=PUBLIC",
      "spring.datasource.url=jdbc:h2:mem:axon-dev-token;DB_CLOSE_DELAY=-1",
      "feature.disable-security=false",
      "feature.enable-dev-token=true",
      "ENTRA_ISSUER_URI=" + TestJwtDecoderConfig.ISSUER_URI,
      "ENTRA_AUD=" + TestJwtDecoderConfig.AUDIENCE
    })
@AutoConfigureTestRestTemplate
class DevTokenInMemoryTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void givenSwaggerCaseworkerToken_whenGetUnknownPriorAuthority_thenReturnsNotFound() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.setBearerAuth("swagger-caseworker-token");

    ResponseEntity<String> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/prior-authorities/" + UUID.randomUUID(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
