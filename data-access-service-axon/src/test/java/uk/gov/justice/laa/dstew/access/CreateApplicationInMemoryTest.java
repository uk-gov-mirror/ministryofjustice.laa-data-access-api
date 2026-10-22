package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.justice.laa.dstew.access.testutils.ApplicationCreateRequestFixture.validApplicationContent;
import static uk.gov.justice.laa.dstew.access.testutils.ApplicationCreateRequestFixture.validCreateApplicationRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import uk.gov.justice.laa.dstew.access.command.application.AutoGrantedState;
import uk.gov.justice.laa.dstew.access.command.application.data.ApplicationDataId;
import uk.gov.justice.laa.dstew.access.command.application.data.ApplicationDataRepository;
import uk.gov.justice.laa.dstew.access.command.application.linkedgroup.route.ApplicationGroupRouteKind;
import uk.gov.justice.laa.dstew.access.command.application.linkedgroup.route.ApplicationGroupRouteRepository;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.model.ApplicationHistoryResponse;
import uk.gov.justice.laa.dstew.access.model.ApplicationResponse;
import uk.gov.justice.laa.dstew.access.model.ApplicationStatus;
import uk.gov.justice.laa.dstew.access.model.ApplicationSummaryResponse;
import uk.gov.justice.laa.dstew.access.model.ApplicationUpdateRequest;
import uk.gov.justice.laa.dstew.access.model.AutoGrantOutcome;
import uk.gov.justice.laa.dstew.access.model.AutoGrantedOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.DecisionStatusResponse;
import uk.gov.justice.laa.dstew.access.model.DomainEventType;
import uk.gov.justice.laa.dstew.access.model.IndividualsResponse;
import uk.gov.justice.laa.dstew.access.model.ManualOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionStatusResponse;
import uk.gov.justice.laa.dstew.access.model.PotentialDuplicate;
import uk.gov.justice.laa.dstew.access.model.WorkListResponse;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadModel;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadRepository;
import uk.gov.justice.laa.dstew.access.query.application.FindApplicationByIdQuery;
import uk.gov.justice.laa.dstew.access.query.application.history.ApplicationHistoryReadModel;
import uk.gov.justice.laa.dstew.access.query.application.history.ApplicationHistoryReadRepository;
import uk.gov.justice.laa.dstew.access.query.application.linkedgroup.LinkedApplicationGroupReadRepository;
import uk.gov.justice.laa.dstew.access.testsupport.TestJwtDecoderConfig;

@SpringBootTest(
    classes = DataAccessServiceAxonApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.default_schema=PUBLIC",
      "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
      "spring.datasource.url=jdbc:h2:mem:axon-create;DB_CLOSE_DELAY=-1",
      "feature.disable-security=false",
      "feature.enable-dev-token=false"
    })
@AutoConfigureTestRestTemplate
@Import(TestJwtDecoderConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CreateApplicationInMemoryTest {

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private ApplicationReadRepository applicationReadRepository;
  @Autowired private ApplicationHistoryReadRepository applicationHistoryReadRepository;
  @Autowired private LinkedApplicationGroupReadRepository groupReadRepository;
  @Autowired private ApplicationGroupRouteRepository routeRepository;
  @Autowired private AxonConfiguration axonConfiguration;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private QueryGateway queryGateway;
  @Autowired private ApplicationDataRepository applicationDataRepository;

  @ParameterizedTest
  @CsvSource({
    "test-auto-grant-service-token, MANUAL",
    "test-auto-grant-service-token, AUTOGRANTED",
    "test-obo-without-role-token, MANUAL"
  })
  void givenAutoGrantOrOboCaller_whenReadingAndRecordingOutcome_thenSucceeds(
      String token, String outcome) {
    UUID applicationId = UUID.randomUUID();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class));
    awaitProjection(applicationId);
    HttpHeaders serviceHeaders = headers(token);

    ResponseEntity<ApplicationResponse> read =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.GET,
            new HttpEntity<>(serviceHeaders),
            ApplicationResponse.class);
    assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(read.getBody()).isNotNull();
    assertThat(read.getBody().getApplicationId()).isEqualTo(applicationId);

    Map<String, Object> request =
        "MANUAL".equals(outcome)
            ? Map.of("outcome", outcome)
            : Map.of("outcome", outcome, "certificate", Map.of("certificateNumber", "M2M-1"));
    ResponseEntity<String> write =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(request, serviceHeaders),
            String.class);

    assertThat(write.getStatusCode())
        .as("response: %s", write.getBody())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(awaitApplicationVersion(applicationId, 1L).getAutoGranted())
        .isEqualTo(AutoGrantedState.valueOf(outcome));
  }

  @Test
  void givenAppOnlyAutoGrantRole_whenCallingOtherEndpoints_thenReturnsForbidden() {
    String applicationPath = "/api/v0/applications/" + UUID.randomUUID();
    HttpHeaders serviceHeaders = headers(TestJwtDecoderConfig.AUTO_GRANT_BEARER_TOKEN);
    for (String path :
        List.of(
            "/api/v0/applications",
            applicationPath + "/certificate",
            applicationPath + "/history-search",
            applicationPath + "/notes",
            applicationPath + "/documents/" + UUID.randomUUID(),
            "/api/v0/work-list",
            "/api/v0/individuals")) {
      assertThat(
              restTemplate
                  .exchange(path, HttpMethod.GET, new HttpEntity<>(serviceHeaders), String.class)
                  .getStatusCode())
          .as("GET %s", path)
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
    for (String path : List.of(applicationPath, applicationPath + "/decision")) {
      assertThat(
              restTemplate
                  .exchange(
                      path,
                      HttpMethod.PATCH,
                      new HttpEntity<>(Map.of(), serviceHeaders),
                      String.class)
                  .getStatusCode())
          .as("PATCH %s", path)
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
    assertThat(
            restTemplate
                .postForEntity(
                    "/api/v0/applications",
                    new HttpEntity<>(
                        validCreateApplicationRequest(UUID.randomUUID(), UUID.randomUUID()),
                        serviceHeaders),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"test-other-service-token", "invalid-token", ""})
  void givenMissingAutoGrantPermission_whenReadingOrRecordingOutcome_thenDeniesAccess(
      String token) {
    String path = "/api/v0/applications/" + UUID.randomUUID();
    HttpHeaders requestHeaders = headers(token);
    if (token.isEmpty()) {
      requestHeaders.remove(HttpHeaders.AUTHORIZATION);
    }
    HttpStatus expected =
        TestJwtDecoderConfig.OTHER_SERVICE_BEARER_TOKEN.equals(token)
            ? HttpStatus.FORBIDDEN
            : HttpStatus.UNAUTHORIZED;

    assertThat(
            restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(requestHeaders), String.class)
                .getStatusCode())
        .isEqualTo(expected);
    assertThat(
            restTemplate
                .exchange(
                    path + "/auto-grant-outcome",
                    HttpMethod.PATCH,
                    new HttpEntity<>(
                        new ManualOutcomeRequest(AutoGrantOutcome.MANUAL), requestHeaders),
                    String.class)
                .getStatusCode())
        .isEqualTo(expected);
  }

  @Test
  void givenCaseworkerWithoutDelegatedScope_whenReadingApplications_thenReturnsForbidden() {
    for (String path :
        List.of(
            "/api/v0/applications",
            "/api/v0/applications/" + UUID.randomUUID(),
            "/api/v0/applications/" + UUID.randomUUID() + "/documents/" + UUID.randomUUID())) {
      assertThat(
              restTemplate
                  .exchange(
                      path,
                      HttpMethod.GET,
                      new HttpEntity<>(headers(TestJwtDecoderConfig.UNSCOPED_BEARER_TOKEN)),
                      String.class)
                  .getStatusCode())
          .as("GET %s", path)
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
  }

  @Test
  void givenApplicationInProgress_whenUpdatedToSubmitted_thenCurrentStateAdvancesVersion() {
    UUID applicationId = UUID.randomUUID();
    ApplicationCreateRequest submitted =
        validCreateApplicationRequest(applicationId, UUID.randomUUID());
    ApplicationCreateRequest inProgress =
        submitted.toBuilder().status(ApplicationStatus.APPLICATION_IN_PROGRESS).build();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications", new HttpEntity<>(inProgress, headers()), Void.class));
    awaitProjection(applicationId);
    ApplicationUpdateRequest update =
        new ApplicationUpdateRequest()
            .status(ApplicationStatus.APPLICATION_SUBMITTED)
            .applicationContent(submitted.getApplicationContent());

    ResponseEntity<Void> updateResponse =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.PATCH,
            new HttpEntity<>(update, headers()),
            Void.class);

    assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ApplicationReadModel current = awaitApplicationVersion(applicationId, 1L);
    assertThat(current.getStatus()).isEqualTo("APPLICATION_SUBMITTED");
    assertThat(current.getAutoGranted()).isEqualTo(AutoGrantedState.PENDING);
    assertThat(current.getApplicationDataVersion()).isEqualTo(1L);
    assertThat(awaitHistoryTypes(applicationId, "APPLICATION_CREATED", "APPLICATION_UPDATED"))
        .hasSize(2);
  }

  @Test
  void givenAxonControllers_whenOpenApiRequested_thenDocumentsBearerSecuredEndpoints()
      throws Exception {
    ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .contains("\"openapi\":\"3.1")
        .contains("\"/api/v0/applications\"")
        .contains("\"/api/v0/individuals\"");

    JsonNode openApi = new ObjectMapper().readTree(response.getBody());
    assertThat(openApi.at("/components/securitySchemes/BearerAuth/type").asText())
        .isEqualTo("http");
    assertThat(openApi.at("/components/securitySchemes/BearerAuth/scheme").asText())
        .isEqualTo("bearer");
    assertThat(openApi.at("/components/securitySchemes/bearerAuth").isMissingNode()).isTrue();
    assertThat(openApi.at("/paths/~1api~1v0~1individuals/get/security/0/BearerAuth").isArray())
        .isTrue();
  }

  @Test
  void givenLinkApplicationOperation_whenOpenApiRequested_thenRequestBodyExamplesArePresent()
      throws Exception {
    ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode openApi = new ObjectMapper().readTree(response.getBody());
    JsonNode examples =
        openApi.at(
            "/paths/~1api~1v0~1applications~1{id}~1link/post/requestBody/content/"
                + "application~1json/examples");

    assertThat(examples.has("firstTimeLink")).isTrue();
    assertThat(examples.at("/firstTimeLink/value/linkedGroupVersion").isMissingNode()).isTrue();
    assertThat(examples.at("/targetAlreadyLinked/value/linkedGroupVersion").asText())
        .isEqualTo("djE6bGlua2VkLWdyb3VwOjdjOWU2Njc5LTc0MjUtNDBkZS05NDRiLWUwN2ZjMWY5MGFlNzoy");
  }

  @Test
  void givenUnknownApplication_whenGetApplication_thenReturnsNotFound() {
    UUID applicationId = UUID.randomUUID();

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains("No application found with ID: " + applicationId);
  }

  @Test
  void givenCreatedApplication_whenGetApplicationHistory_thenReturnsApiVisibleEvents() {
    UUID applicationId = UUID.randomUUID();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class));
    awaitHistoryTypes(applicationId, "APPLICATION_CREATED");

    ResponseEntity<ApplicationHistoryResponse> response =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/history-search",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationHistoryResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getEvents())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getApplicationId()).isEqualTo(applicationId);
              assertThat(event.getDomainEventType()).isEqualTo(DomainEventType.APPLICATION_CREATED);
              assertThat(event.getCreatedBy()).isEqualTo("CIVIL_APPLY");
              assertThat(event.getCreatedAt()).isNotNull();
            });
  }

  @Test
  void givenNonMatchingEventType_whenGetApplicationHistory_thenReturnsEmptyEvents() {
    UUID applicationId = UUID.randomUUID();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class));
    awaitHistoryTypes(applicationId, "APPLICATION_CREATED");

    ResponseEntity<ApplicationHistoryResponse> response =
        restTemplate.exchange(
            "/api/v0/applications/"
                + applicationId
                + "/history-search?eventType=APPLICATION_UPDATED",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationHistoryResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getEvents()).isEmpty();
  }

  @Test
  void givenMissingServiceName_whenGetApplicationHistory_thenReturnsBadRequest() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(TestJwtDecoderConfig.BEARER_TOKEN);
    ResponseEntity<String> response =
        restTemplate.exchange(
            "/api/v0/applications/" + UUID.randomUUID() + "/history-search",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void givenCreatedApplication_whenGetIndividualsForApplication_thenReturnsEmptyIndividuals() {
    UUID applicationId = UUID.randomUUID();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class));
    awaitProjection(applicationId);

    ResponseEntity<IndividualsResponse> response =
        restTemplate.exchange(
            "/api/v0/individuals?applicationId=" + applicationId + "&individualType=CLIENT",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            IndividualsResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getIndividuals())
        .singleElement()
        .satisfies(
            individual -> {
              assertThat(individual.getFirstName()).isEqualTo("Ada");
              assertThat(individual.getLastName()).isEqualTo("Lovelace");
              assertThat(individual.getType().name()).isEqualTo("CLIENT");
            });
    assertThat(response.getBody().getPaging().getPage()).isEqualTo(1);
    assertThat(response.getBody().getPaging().getPageSize()).isEqualTo(20);
    assertThat(response.getBody().getPaging().getTotalRecords()).isEqualTo(1);
    assertThat(response.getBody().getPaging().getItemsReturned()).isEqualTo(1);
  }

  @Test
  void givenClientDetailsWithoutApplicationId_whenGetIndividuals_thenReturnsBadRequest() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            "/api/v0/individuals?include=CLIENT_DETAILS",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .contains("Application ID is required when included data is CLIENT_DETAILS");
  }

  @Test
  void givenValidRequest_whenPostApplication_thenReturnsCreatedAndProjectsOwnedState() {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    ApplicationCreateRequest request =
        validCreateApplicationRequest(applicationId, applyProceedingId);
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.set("X-Schema-Version", "1");
    headers.setBearerAuth(TestJwtDecoderConfig.BEARER_TOKEN);

    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "/api/v0/applications", new HttpEntity<>(request, headers), Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getLocation()).isNotNull();
    assertThat(response.getHeaders().getLocation().getPath())
        .isEqualTo("/api/v0/applications/" + applicationId);

    UUID createdApplicationId =
        UUID.fromString(
            response.getHeaders().getLocation().getPath().replace("/api/v0/applications/", ""));
    assertThat(createdApplicationId).isEqualTo(applicationId);
    ApplicationReadModel projected = awaitProjection(createdApplicationId);

    assertThat(projected.getApplicationId()).isEqualTo(createdApplicationId);
    assertThat(projected.getStatus()).isEqualTo(ApplicationStatus.APPLICATION_SUBMITTED.name());
    assertThat(projected.getAutoGranted()).isEqualTo(AutoGrantedState.PENDING);
    assertThat(projected.getLaaReference()).isEqualTo("LAA-123");
    assertThat(projected.getOfficeCode()).isEqualTo("1A001B");
    assertThat(projected.getSchemaVersion()).isEqualTo(1);
    assertThat(projected.getPotentialDuplicates()).isEmpty();
    assertThat(projected.getProceedings())
        .singleElement()
        .satisfies(
            proceeding -> {
              assertThat(proceeding.getId()).isEqualTo(applyProceedingId);
              assertThat(proceeding.getLeadProceeding()).isTrue();
              assertThat(proceeding.getDescription()).isEqualTo("Care order");
            });

    assertThat(awaitHistory(createdApplicationId, 1))
        .singleElement()
        .satisfies(
            history -> {
              assertThat(history.getEventType()).isEqualTo("APPLICATION_CREATED");
              assertThat(history.getDataVersion()).isZero();
              assertThat(history.getServiceName()).isEqualTo("CIVIL_APPLY");
            });

    assertThat(applicationDataRepository.findById(new ApplicationDataId(createdApplicationId, 0L)))
        .isPresent()
        .hasValueSatisfying(
            data -> {
              assertThat(data.getPayload().laaReference()).isEqualTo("LAA-123");
              assertThat(data.getPayloadHash()).hasSize(64);
            });

    var processors = axonConfiguration.getComponents(StreamingEventProcessor.class);
    assertThat(processors.get("application-projection")).isNotNull();
    assertThat(processors.get("application-history-projection")).isNotNull();
  }

  @Test
  void givenSubmittedApplication_whenMarkedReady_thenOnlyFalseOutcomeEntersManualList() {
    UUID applicationId = UUID.randomUUID();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class));
    awaitProjection(applicationId);

    ResponseEntity<ApplicationResponse> directBeforeReady =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationResponse.class);
    assertThat(directBeforeReady.getBody()).isNotNull();
    assertThat(directBeforeReady.getBody().getAutoGranted())
        .isEqualTo(uk.gov.justice.laa.dstew.access.model.AutoGranted.PENDING);

    ResponseEntity<ApplicationSummaryResponse> before =
        restTemplate.exchange(
            "/api/v0/applications?status=APPLICATION_SUBMITTED&autoGranted=MANUAL",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationSummaryResponse.class);
    assertThat(before.getBody()).isNotNull();
    assertThat(before.getBody().getApplications())
        .extracting(application -> application.getApplicationId())
        .doesNotContain(applicationId);

    ManualOutcomeRequest request = new ManualOutcomeRequest(AutoGrantOutcome.MANUAL);
    ResponseEntity<Void> ready =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);
    assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    ApplicationReadModel projected = awaitApplicationVersion(applicationId, 1L);
    assertThat(projected.getStatus()).isEqualTo(ApplicationStatus.APPLICATION_SUBMITTED.name());
    assertThat(projected.getAutoGranted()).isEqualTo(AutoGrantedState.MANUAL);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              ResponseEntity<WorkListResponse> workList =
                  restTemplate.exchange(
                      "/api/v0/work-list",
                      HttpMethod.GET,
                      new HttpEntity<>(headers()),
                      WorkListResponse.class);
              assertThat(workList.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(workList.getBody()).isNotNull();
              assertThat(workList.getBody().getItems())
                  .extracting(item -> item.getItemId())
                  .contains(applicationId);
            });

    ResponseEntity<ApplicationResponse> direct =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationResponse.class);
    assertThat(direct.getBody()).isNotNull();
    assertThat(direct.getBody().getStatus()).isEqualTo(ApplicationStatus.APPLICATION_SUBMITTED);
    assertThat(direct.getBody().getAutoGranted())
        .isEqualTo(uk.gov.justice.laa.dstew.access.model.AutoGranted.MANUAL);

    ResponseEntity<ApplicationSummaryResponse> after =
        restTemplate.exchange(
            "/api/v0/applications?status=APPLICATION_SUBMITTED&autoGranted=MANUAL",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationSummaryResponse.class);
    assertThat(after.getBody()).isNotNull();
    assertThat(after.getBody().getApplications())
        .filteredOn(application -> application.getApplicationId().equals(applicationId))
        .singleElement()
        .satisfies(
            application ->
                assertThat(application.getAutoGranted())
                    .isEqualTo(uk.gov.justice.laa.dstew.access.model.AutoGranted.MANUAL));

    ResponseEntity<Void> replay =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void givenSubmittedApplication_whenAutomaticallyGranted_thenCompleteDecisionIsRecorded() {
    UUID applicationId = UUID.randomUUID();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class));
    ApplicationResponse created =
        restTemplate
            .exchange(
                "/api/v0/applications/" + applicationId,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                ApplicationResponse.class)
            .getBody();
    assertThat(created).isNotNull();
    var request =
        new AutoGrantedOutcomeRequest(
            AutoGrantOutcome.AUTOGRANTED, Map.of("certificateNumber", "AUTO-2126"));

    ResponseEntity<Void> response =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    awaitApplicationVersion(applicationId, 1L);
    ApplicationResponse granted =
        restTemplate
            .exchange(
                "/api/v0/applications/" + applicationId,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                ApplicationResponse.class)
            .getBody();
    assertThat(granted).isNotNull();
    assertThat(granted.getAutoGranted())
        .isEqualTo(uk.gov.justice.laa.dstew.access.model.AutoGranted.AUTOGRANTED);
    assertThat(granted.getDecisionStatus()).isEqualTo(DecisionStatusResponse.GRANTED);
    assertThat(granted.getProceedings())
        .singleElement()
        .satisfies(
            proceeding ->
                assertThat(proceeding.getMeritsDecision())
                    .isEqualTo(MeritsDecisionStatusResponse.GRANTED));
    assertThat(applicationDataRepository.findById(new ApplicationDataId(applicationId, 1L)))
        .get()
        .extracting(data -> data.getPayload().certificate())
        .isEqualTo(Map.of("certificateNumber", "AUTO-2126"));

    ResponseEntity<Void> repeated =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);
    assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(applicationDataRepository.countByIdApplicationId(applicationId)).isEqualTo(2L);
  }

  @Test
  void givenVersionlessManualOutcome_whenMarkedReady_thenUsesCurrentApplication() {
    UUID applicationId = UUID.randomUUID();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class));

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(new ManualOutcomeRequest(AutoGrantOutcome.MANUAL), headers()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void givenApplicationInProgress_whenMarkedReady_thenReturnsUnprocessableEntity() {
    UUID applicationId = UUID.randomUUID();
    ApplicationCreateRequest submitted =
        validCreateApplicationRequest(applicationId, UUID.randomUUID());
    ApplicationCreateRequest inProgress =
        submitted.toBuilder().status(ApplicationStatus.APPLICATION_IN_PROGRESS).build();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications", new HttpEntity<>(inProgress, headers()), Void.class));

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(new ManualOutcomeRequest(AutoGrantOutcome.MANUAL), headers()),
            String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(422);
    assertThat(response.getBody()).contains("APPLICATION_IN_PROGRESS");
  }

  @Test
  void givenAutomaticallyGrantedApplication_whenMarkedReady_thenReturnsConflict() {
    UUID applicationId = UUID.randomUUID();
    applicationId(
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class));
    ResponseEntity<Void> autoGrantResponse =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(
                new AutoGrantedOutcomeRequest(
                    AutoGrantOutcome.AUTOGRANTED, java.util.Map.of("certificateNumber", "AUTO-1")),
                headers()),
            Void.class);
    assertThat(autoGrantResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(new ManualOutcomeRequest(AutoGrantOutcome.MANUAL), headers()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).contains("incompatible auto-grant outcome");
  }

  @Test
  void givenSchemaInvalidRequest_whenPostApplication_thenReturnsBadRequestAndNoProjection() {
    UUID applicationId = UUID.randomUUID();
    ApplicationCreateRequest validRequest =
        validCreateApplicationRequest(applicationId, UUID.randomUUID());
    var invalidContent = new java.util.HashMap<>(validRequest.getApplicationContent());
    invalidContent.remove("submittedAt");
    validRequest.setApplicationContent(invalidContent);

    ResponseEntity<String> response =
        restTemplate.postForEntity(
            "/api/v0/applications", new HttpEntity<>(validRequest, headers()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("Generic Validation Error");
    assertThat(applicationReadRepository.findById(applicationId)).isEmpty();
  }

  @Test
  void givenIdenticalRetry_whenPostApplicationAgain_thenReturnsCreatedIdempotently() {
    UUID applicationId = UUID.randomUUID();
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.set("X-Schema-Version", "1");
    headers.setBearerAuth(TestJwtDecoderConfig.BEARER_TOKEN);
    HttpEntity<ApplicationCreateRequest> request =
        new HttpEntity<>(validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers);

    ResponseEntity<Void> firstResponse =
        restTemplate.postForEntity("/api/v0/applications", request, Void.class);
    awaitProjection(applicationId(firstResponse));
    ResponseEntity<Void> retryResponse =
        restTemplate.postForEntity("/api/v0/applications", request, Void.class);

    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retryResponse.getHeaders().getLocation())
        .isEqualTo(firstResponse.getHeaders().getLocation());

    UUID createdApplicationId = applicationId(firstResponse);
    assertThat(awaitHistory(createdApplicationId, 1))
        .singleElement()
        .satisfies(h -> assertThat(h.getEventType()).isEqualTo("APPLICATION_CREATED"));
  }

  @Test
  void givenChangedPayload_whenPostApplicationAgain_thenReturnsConflict() {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.set("X-Schema-Version", "1");
    headers.setBearerAuth(TestJwtDecoderConfig.BEARER_TOKEN);

    ResponseEntity<Void> firstResponse =
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, applyProceedingId), headers),
            Void.class);
    awaitProjection(applicationId(firstResponse));

    ResponseEntity<String> conflictResponse =
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers),
            String.class);

    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(awaitHistory(applicationId, 1)).hasSize(1);
  }

  @Test
  void givenApplicationContent_whenPostApplication_thenCreatesStandaloneRouteOnly() {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    ApplicationCreateRequest request =
        validCreateApplicationRequest(applicationId, applyProceedingId);

    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "/api/v0/applications", new HttpEntity<>(request, headers()), Void.class);
    UUID createdApplicationId = applicationId(response);
    ApplicationReadModel projected = awaitProjection(createdApplicationId);

    assertThat(projected.getLeadApplicationId()).isNull();
    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(50, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                assertThat(routeRepository.findById(createdApplicationId))
                    .isPresent()
                    .hasValueSatisfying(
                        route -> {
                          assertThat(route.getRouteKind())
                              .isEqualTo(ApplicationGroupRouteKind.STANDALONE);
                          assertThat(route.getGroupId()).isNull();
                        }));
    assertThat(groupReadRepository.count()).isZero();
    assertThat(awaitHistory(createdApplicationId, 1))
        .singleElement()
        .satisfies(history -> assertThat(history.getEventType()).isEqualTo("APPLICATION_CREATED"));
  }

  @Test
  void givenNoMatchingApplications_whenGetApplicationsFilteredByLaaReference_thenReturnsEmpty() {
    ResponseEntity<ApplicationSummaryResponse> response =
        restTemplate.exchange(
            "/api/v0/applications?laaReference=DOES-NOT-EXIST-12345",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationSummaryResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getApplications()).isEmpty();
    assertThat(response.getBody().getPaging().getTotalRecords()).isZero();
  }

  @Test
  void givenCreatedApplication_whenGetApplications_thenReturnsSummary() {
    UUID applicationId = UUID.randomUUID();
    ResponseEntity<Void> postResponse =
        restTemplate.postForEntity(
            "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()),
            Void.class);
    UUID createdApplicationId = applicationId(postResponse);
    awaitProjection(createdApplicationId);

    ResponseEntity<ApplicationSummaryResponse> response =
        restTemplate.exchange(
            "/api/v0/applications",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationSummaryResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().getApplications())
        .anySatisfy(
            summary -> {
              assertThat(summary.getApplicationId()).isEqualTo(createdApplicationId);
              assertThat(summary.getLaaReference()).isEqualTo("LAA-123");
              assertThat(summary.getIsLead()).isFalse();
              assertThat(summary.getClientFirstName()).isEqualTo("Ada");
              assertThat(summary.getClientLastName()).isEqualTo("Lovelace");
            });
  }

  @Test
  void
      givenApplicationWithPopulatedPotentialDuplicates_whenCreatedAndRetrieved_thenPotentialDuplicatesArePersisted() {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    UUID duplicateId1 = UUID.randomUUID();
    UUID duplicateId2 = UUID.randomUUID();
    List<PotentialDuplicate> potentialDuplicates =
        List.of(
            PotentialDuplicate.builder()
                .applicationId(duplicateId1)
                .laaReference("LAA-00001")
                .legacyReference("LEGACY-001")
                .build(),
            PotentialDuplicate.builder()
                .applicationId(duplicateId2)
                .laaReference("LAA-00002")
                .legacyReference(null)
                .build());
    ApplicationCreateRequest request =
        ApplicationCreateRequest.builder()
            .id(applicationId)
            .status(ApplicationStatus.APPLICATION_SUBMITTED)
            .applicationContent(validApplicationContent(applicationId, applyProceedingId))
            .laaReference("LAA-123")
            .potentialDuplicates(potentialDuplicates)
            .build();

    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "/api/v0/applications", new HttpEntity<>(request, headers()), Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ApplicationReadModel projected = awaitProjection(applicationId);

    assertThat(projected.getApplicationId()).isEqualTo(applicationId);
    assertThat(projected.getPotentialDuplicates()).hasSize(2);
    assertThat(projected.getPotentialDuplicates().get(0).getApplicationId())
        .isEqualTo(duplicateId1);
    assertThat(projected.getPotentialDuplicates().get(0).getLaaReference()).isEqualTo("LAA-00001");
    assertThat(projected.getPotentialDuplicates().get(0).getLegacyReference())
        .isEqualTo("LEGACY-001");
    assertThat(projected.getPotentialDuplicates().get(1).getApplicationId())
        .isEqualTo(duplicateId2);
    assertThat(projected.getPotentialDuplicates().get(1).getLaaReference()).isEqualTo("LAA-00002");
    assertThat(projected.getPotentialDuplicates().get(1).getLegacyReference()).isNull();

    ResponseEntity<ApplicationResponse> getResponse =
        restTemplate.exchange(
            "/api/v0/applications/" + applicationId,
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationResponse.class);

    assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getResponse.getBody()).isNotNull();
    assertThat(getResponse.getBody().getPotentialDuplicates()).hasSize(2);
    assertThat(getResponse.getBody().getPotentialDuplicates().get(0).getApplicationId())
        .isEqualTo(duplicateId1);
    assertThat(getResponse.getBody().getPotentialDuplicates().get(0).getLaaReference())
        .isEqualTo("LAA-00001");
  }

  private HttpHeaders headers() {
    return headers(TestJwtDecoderConfig.BEARER_TOKEN);
  }

  private HttpHeaders headers(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.set("X-Schema-Version", "1");
    headers.setBearerAuth(token);
    return headers;
  }

  private UUID applicationId(ResponseEntity<Void> response) {
    assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getLocation()).isNotNull();
    return UUID.fromString(
        response.getHeaders().getLocation().getPath().replace("/api/v0/applications/", ""));
  }

  private ResponseEntity<ApplicationHistoryResponse> getApplicationHistory(
      UUID applicationId, DomainEventType eventType) {
    String path = "/api/v0/applications/" + applicationId + "/history-search";
    if (eventType != null) {
      path += "?eventType=" + eventType.getValue();
    }
    return restTemplate.exchange(
        path, HttpMethod.GET, new HttpEntity<>(headers()), ApplicationHistoryResponse.class);
  }

  private ApplicationReadModel awaitProjection(UUID applicationId) {
    return await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(50, TimeUnit.MILLISECONDS)
        .until(
            () ->
                queryGateway
                    .query(new FindApplicationByIdQuery(applicationId), ApplicationReadModel.class)
                    .join(),
            Objects::nonNull);
  }

  private ApplicationReadModel awaitApplicationVersion(UUID applicationId, long version) {
    return await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(50, TimeUnit.MILLISECONDS)
        .until(
            () ->
                queryGateway
                    .query(new FindApplicationByIdQuery(applicationId), ApplicationReadModel.class)
                    .join(),
            application -> application != null && application.getApplicationVersion() == version);
  }

  private List<ApplicationHistoryReadModel> awaitHistory(UUID applicationId, int expectedCount) {
    return await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(50, TimeUnit.MILLISECONDS)
        .until(
            () ->
                applicationHistoryReadRepository.findAllByApplicationIdOrderByOccurredAtAsc(
                    applicationId),
            history -> history.size() == expectedCount);
  }

  private List<ApplicationHistoryReadModel> awaitHistoryTypes(
      UUID applicationId, String... expectedEventTypes) {
    List<String> expected = List.of(expectedEventTypes);
    return await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(50, TimeUnit.MILLISECONDS)
        .until(
            () ->
                applicationHistoryReadRepository.findAllByApplicationIdOrderByOccurredAtAsc(
                    applicationId),
            history ->
                history.size() == expected.size()
                    && new java.util.HashSet<>(
                            history.stream()
                                .map(ApplicationHistoryReadModel::getEventType)
                                .toList())
                        .equals(new java.util.HashSet<>(expected)));
  }

  private void assertRejectedApplicationWasRolledBack(UUID applicationId) {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_entry WHERE aggregate_identifier = ?",
                Integer.class,
                applicationId.toString()))
        .isZero();
    assertThat(applicationDataRepository.countByIdApplicationId(applicationId)).isZero();
    assertThat(applicationReadRepository.findById(applicationId)).isEmpty();
    assertThat(applicationHistoryReadRepository.countByApplicationId(applicationId)).isZero();
  }
}
