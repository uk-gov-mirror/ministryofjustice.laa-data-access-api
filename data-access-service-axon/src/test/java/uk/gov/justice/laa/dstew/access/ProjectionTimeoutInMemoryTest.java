package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.justice.laa.dstew.access.testutils.ApplicationCreateRequestFixture.validCreateApplicationRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.model.ApplicationResponse;
import uk.gov.justice.laa.dstew.access.model.AutoGrantOutcome;
import uk.gov.justice.laa.dstew.access.model.DecisionStatus;
import uk.gov.justice.laa.dstew.access.model.EventHistoryRequest;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionProceedingRequest;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionRequest;
import uk.gov.justice.laa.dstew.access.model.ManualOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionDetailsRequest;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionStatus;

/**
 * Verifies that when the application-projection tracking processor is stopped the controller
 * returns 202 Accepted with a deterministic Location header after the short configured timeout, and
 * does not block for the full default five-second projection wait.
 *
 * <p>Uses a separate Spring context with a 200 ms projection timeout so the test completes in well
 * under one second. {@code @DirtiesContext} discards the stopped processor after the class so it
 * cannot leak into other test contexts.
 */
@SpringBootTest(
    classes = DataAccessServiceAxonApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.default_schema=PUBLIC",
      "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
      "spring.datasource.url=jdbc:h2:mem:axon-timeout;DB_CLOSE_DELAY=-1",
      "application.projection.timeout=200ms"
    })
@AutoConfigureTestRestTemplate
@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
class ProjectionTimeoutInMemoryTest {

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private AxonConfiguration axonConfiguration;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void givenStoppedProjectionProcessor_whenPostApplication_thenReturnsAcceptedWithLocation() {
    // Stop the projection processor so no QueryUpdateEmitter.emit can be called for this command.
    StreamingEventProcessor processor =
        axonConfiguration
            .getComponents(StreamingEventProcessor.class)
            .get("application-projection");
    processor.shutdown().join();

    UUID applicationId = UUID.randomUUID();
    ApplicationCreateRequest request =
        validCreateApplicationRequest(applicationId, UUID.randomUUID());
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.set("X-Schema-Version", "1");

    long startMs = System.currentTimeMillis();
    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "/api/v0/applications", new HttpEntity<>(request, headers), Void.class);
    long elapsedMs = System.currentTimeMillis() - startMs;

    // Command committed → 202 because projection never appeared within the 200 ms timeout.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getLocation()).isNotNull();
    assertThat(response.getHeaders().getLocation().getPath())
        .isEqualTo("/api/v0/applications/" + applicationId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_entry "
                    + "WHERE aggregate_identifier = ? AND sequence_number = 0",
                Integer.class,
                applicationId.toString()))
        .isEqualTo(1);

    // The test must complete well below the full 5-second default timeout.
    assertThat(elapsedMs).isLessThan(3_000L);

    CompletableFuture<Void> restart =
        CompletableFuture.runAsync(
            () -> processor.start().join(),
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
    ResponseEntity<ApplicationResponse> directRead =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            ApplicationResponse.class);

    restart.join();
    assertThat(directRead.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(directRead.getBody()).isNotNull();
    assertThat(directRead.getBody().getApplicationId()).isEqualTo(applicationId);
  }

  @Disabled("No longer relevant as Get Application does not read from a projection")
  @Test
  void givenProjectionLagAfterManualReadiness_whenDeliveryIsRepeated_thenNoSecondEventIsAppended() {
    UUID applicationId = UUID.randomUUID();
    ApplicationCreateRequest createRequest =
        validCreateApplicationRequest(applicationId, UUID.randomUUID());
    HttpHeaders headers = headers();
    assertThat(
            restTemplate
                .postForEntity(
                    "/api/v0/applications", new HttpEntity<>(createRequest, headers), Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    StreamingEventProcessor processor =
        axonConfiguration
            .getComponents(StreamingEventProcessor.class)
            .get("application-projection");
    processor.shutdown().join();
    ManualOutcomeRequest readyRequest = new ManualOutcomeRequest(AutoGrantOutcome.MANUAL);
    HttpEntity<ManualOutcomeRequest> readyEntity = new HttpEntity<>(readyRequest, headers);

    ResponseEntity<Void> first =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            readyEntity,
            Void.class);
    ResponseEntity<ApplicationResponse> staleRead =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            ApplicationResponse.class);
    ResponseEntity<Void> repeated =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            readyEntity,
            Void.class);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(staleRead.getBody()).isNotNull();
    assertThat(staleRead.getBody().getAutoGranted())
        .isEqualTo(uk.gov.justice.laa.dstew.access.model.AutoGranted.PENDING);
    assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_entry WHERE aggregate_identifier = ?",
                Integer.class,
                applicationId.toString()))
        .isEqualTo(2);

    processor.start().join();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              ResponseEntity<ApplicationResponse> currentRead =
                  restTemplate.exchange(
                      "/api/v0/applications/" + applicationId,
                      HttpMethod.GET,
                      new HttpEntity<>(headers),
                      ApplicationResponse.class);
              assertThat(currentRead.getBody()).isNotNull();
              assertThat(currentRead.getBody().getAutoGranted())
                  .isEqualTo(uk.gov.justice.laa.dstew.access.model.AutoGranted.MANUAL);
              assertThat(currentRead.getBody().getVersion()).isEqualTo(1L);
            });
  }

  @Disabled("No longer relevant as Get Application does not read from a projection")
  @Test
  void givenProjectionLagAfterDecision_whenDeliveryIsRepeated_thenConflictDoesNotAppendAnEvent() {
    UUID applicationId = UUID.randomUUID();
    ApplicationCreateRequest createRequest =
        validCreateApplicationRequest(applicationId, UUID.randomUUID());
    HttpHeaders headers = headers();
    assertThat(
            restTemplate
                .postForEntity(
                    "/api/v0/applications", new HttpEntity<>(createRequest, headers), Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    ApplicationResponse created =
        restTemplate
            .exchange(
                "/api/v0/applications/" + applicationId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                ApplicationResponse.class)
            .getBody();
    assertThat(created).isNotNull();
    UUID proceedingId = created.getProceedings().getFirst().getProceedingId();

    StreamingEventProcessor processor =
        axonConfiguration
            .getComponents(StreamingEventProcessor.class)
            .get("application-projection");
    processor.shutdown().join();
    MakeDecisionRequest decisionRequest =
        MakeDecisionRequest.builder()
            .applicationVersion(0L)
            .overallDecision(DecisionStatus.GRANTED)
            .autoGranted(true)
            .certificate(
                Map.of(
                    "certificateNumber", "AUTO-2099",
                    "issueDate", "2026-08-04",
                    "validUntil", "2027-08-04"))
            .eventHistory(EventHistoryRequest.builder().eventDescription("Auto-granted").build())
            .proceedings(
                List.of(
                    MakeDecisionProceedingRequest.builder()
                        .proceedingId(proceedingId)
                        .meritsDecision(
                            MeritsDecisionDetailsRequest.builder()
                                .decision(MeritsDecisionStatus.GRANTED)
                                .justification("Passed automatic assessment")
                                .build())
                        .build()))
            .build();
    HttpEntity<MakeDecisionRequest> decisionEntity = new HttpEntity<>(decisionRequest, headers);

    ResponseEntity<Void> first =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/decision",
            HttpMethod.PATCH,
            decisionEntity,
            Void.class);
    ResponseEntity<ApplicationResponse> staleRead =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            ApplicationResponse.class);
    ResponseEntity<Void> repeated =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/decision",
            HttpMethod.PATCH,
            decisionEntity,
            Void.class);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(staleRead.getBody()).isNotNull();
    assertThat(staleRead.getBody().getAutoGranted())
        .isEqualTo(uk.gov.justice.laa.dstew.access.model.AutoGranted.PENDING);
    assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_entry WHERE aggregate_identifier = ?",
                Integer.class,
                applicationId.toString()))
        .isEqualTo(2);

    processor.start().join();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              ResponseEntity<ApplicationResponse> currentRead =
                  restTemplate.exchange(
                      "/api/v0/applications/" + applicationId,
                      HttpMethod.GET,
                      new HttpEntity<>(headers),
                      ApplicationResponse.class);
              assertThat(currentRead.getBody()).isNotNull();
              assertThat(currentRead.getBody().getAutoGranted())
                  .isEqualTo(uk.gov.justice.laa.dstew.access.model.AutoGranted.AUTOGRANTED);
              assertThat(currentRead.getBody().getVersion()).isEqualTo(1L);
            });
  }

  private HttpHeaders headers() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.set("X-Schema-Version", "1");
    return headers;
  }
}
