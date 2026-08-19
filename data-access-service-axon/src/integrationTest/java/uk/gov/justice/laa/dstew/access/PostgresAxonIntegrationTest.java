package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static uk.gov.justice.laa.dstew.access.testutils.ApplicationCreateRequestFixture.validCreateApplicationRequest;
import static uk.gov.justice.laa.dstew.access.testutils.ApplicationCreateRequestFixture.validLinkedCreateApplicationRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import uk.gov.justice.laa.dstew.access.command.application.AutoGrantedState;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.model.ApplicationHistoryResponse;
import uk.gov.justice.laa.dstew.access.model.ApplicationProceedingResponse;
import uk.gov.justice.laa.dstew.access.model.ApplicationResponse;
import uk.gov.justice.laa.dstew.access.model.ApplicationStatus;
import uk.gov.justice.laa.dstew.access.model.ApplicationUpdateRequest;
import uk.gov.justice.laa.dstew.access.model.AutoGrantOutcome;
import uk.gov.justice.laa.dstew.access.model.AutoGranted;
import uk.gov.justice.laa.dstew.access.model.AutoGrantedOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.CaseworkerAssignRequest;
import uk.gov.justice.laa.dstew.access.model.CaseworkerUnassignRequest;
import uk.gov.justice.laa.dstew.access.model.CategoryOfLaw;
import uk.gov.justice.laa.dstew.access.model.CreateNoteRequest;
import uk.gov.justice.laa.dstew.access.model.DecisionStatus;
import uk.gov.justice.laa.dstew.access.model.EventHistoryRequest;
import uk.gov.justice.laa.dstew.access.model.InvolvedChildResponse;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionProceedingRequest;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionRequest;
import uk.gov.justice.laa.dstew.access.model.ManualOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.MatterType;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionDetailsRequest;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionStatus;
import uk.gov.justice.laa.dstew.access.model.OpponentResponse;
import uk.gov.justice.laa.dstew.access.model.ProviderResponse;
import uk.gov.justice.laa.dstew.access.model.ScopeLimitationResponse;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadModel;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadRepository;
import uk.gov.justice.laa.dstew.access.query.application.FindApplicationByIdQuery;
import uk.gov.justice.laa.dstew.access.query.application.history.ApplicationHistoryReadModel;
import uk.gov.justice.laa.dstew.access.query.application.history.ApplicationHistoryReadRepository;
import uk.gov.justice.laa.dstew.access.query.application.linkedgroup.LinkedApplicationGroupReadRepository;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresAxonIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private EventStorageEngine eventStorageEngine;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ApplicationReadRepository applicationReadRepository;

  @Autowired private ApplicationHistoryReadRepository applicationHistoryReadRepository;

  @Autowired private LinkedApplicationGroupReadRepository groupReadRepository;

  @Autowired private QueryGateway queryGateway;

  @Autowired private Environment environment;

  @Test
  void givenPostgresAxonStore_whenHealthRequested_thenReportsUp() {
    ResponseEntity<String> response =
        restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

    List<String> axonTables =
        jdbcTemplate.queryForList(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'axon'
              AND table_name IN (
                'domain_event_entry',
                'token_entry'
              )
            ORDER BY table_name
            """,
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
    assertThat(
            environment.getProperty("spring.main.allow-circular-references", Boolean.class, false))
        .isFalse();
    assertThat(eventStorageEngine).isInstanceOf(AggregateBasedJpaEventStorageEngine.class);
    assertThat(axonTables).containsExactly("domain_event_entry", "token_entry");
  }

  @Test
  void givenFreshDatabase_whenFlywayRuns_thenCreatesOnlyTheCurrentSchema() {
    List<String> appliedVersions =
        jdbcTemplate.queryForList(
            """
            SELECT version
            FROM axon.flyway_schema_history
            WHERE success
              AND version IS NOT NULL
            ORDER BY installed_rank
            """,
            String.class);
    List<String> tables =
        jdbcTemplate.queryForList(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'axon'
            ORDER BY table_name
            """,
            String.class);
    List<String> sequences =
        jdbcTemplate.queryForList(
            """
            SELECT sequence_name
            FROM information_schema.sequences
            WHERE sequence_schema = 'axon'
            ORDER BY sequence_name
            """,
            String.class);

    assertThat(appliedVersions).containsExactly("1", "2", "3", "4", "5");
    assertThat(tables)
        .containsExactly(
            "application_current_state",
            "application_data",
            "application_history",
            "application_list_index",
            "caseworkers",
            "domain_event_entry",
            "flyway_schema_history",
            "linked_application_group_current_state",
            "token_entry");
    assertThat(sequences).containsExactly("aggregate-event-global-index-sequence");
    assertThat(
            jdbcTemplate.queryForObject(
                """
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = 'axon'
              AND table_name = 'token_entry'
              AND column_name = 'mask'
            """,
                String.class))
        .isEqualTo("NO");
  }

  @Test
  void givenApplicationData_whenMutated_thenOnlyControlledRetentionDeleteIsAllowed()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()));
    awaitProjection(applicationId);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.application_data WHERE application_id = ? AND version = 0",
                Integer.class,
                applicationId))
        .isEqualTo(1);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE axon.application_data SET payload_hash = ? WHERE application_id = ?",
                    "tampered",
                    applicationId))
        .hasStackTraceContaining("application_data is append-only; UPDATE is prohibited");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM axon.application_data WHERE application_id = ?", applicationId))
        .hasStackTraceContaining("application_data is append-only; DELETE is prohibited");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT axon.delete_application_data_for_retention(?)", Long.class, applicationId))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.application_data WHERE application_id = ?",
                Integer.class,
                applicationId))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.application_current_state WHERE application_id = ?",
                Integer.class,
                applicationId))
        .isEqualTo(1);

    ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + port + "/api/v0/applications/" + applicationId, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    List<String> currentStateColumns =
        jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'axon' AND table_name = 'application_current_state'",
            String.class);
    assertThat(currentStateColumns)
        .contains("application_data_version")
        .doesNotContain(
            "laa_reference",
            "application_content",
            "individuals",
            "submitted_at",
            "office_code",
            "proceedings");
  }

  @Test
  void givenValidRequest_whenPostApplication_thenPersistsEventAndCurrentStateProjection()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.set("X-Schema-Version", "1");

    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications",
            new HttpEntity<>(
                validCreateApplicationRequest(applicationId, applyProceedingId), headers),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID createdApplicationId = applicationId(response);
    assertThat(createdApplicationId).isEqualTo(applicationId);

    ApplicationReadModel projected = awaitProjection(createdApplicationId);
    assertThat(projected.getApplicationId()).isEqualTo(createdApplicationId);
    assertThat(projected.getStatus()).isEqualTo("APPLICATION_SUBMITTED");
    assertThat(projected.getLaaReference()).isEqualTo("LAA-123");
    assertThat(projected.getSchemaVersion()).isEqualTo(1);
    assertThat(projected.getSubmittedAt()).isEqualTo(Instant.parse("2026-07-14T12:30:00Z"));
    assertThat(projected.getOfficeCode()).isEqualTo("1A001B");
    assertThat(projected.getUsedDelegatedFunctions()).isFalse();
    assertThat(projected.getCategoryOfLaw()).isEqualTo("Family");
    assertThat(projected.getMatterType()).isEqualTo("SPECIAL_CHILDREN_ACT");
    assertThat(projected.getCreatedAt()).isNotNull().isEqualTo(projected.getModifiedAt());
    assertThat(projected.getProvider()).isNotNull();
    assertThat(projected.getProvider().getOfficeCode()).isEqualTo("1A001B");
    assertThat(projected.getProceedings())
        .singleElement()
        .satisfies(
            proceeding -> {
              assertThat(proceeding.getId()).isEqualTo(applyProceedingId);
              assertThat(proceeding.getDescription()).isEqualTo("Care order");
              assertThat(proceeding.getLeadProceeding()).isTrue();
            });

    assertThat(awaitHistory(createdApplicationId, 1))
        .singleElement()
        .satisfies(
            history -> {
              assertThat(history.getEventType()).isEqualTo("APPLICATION_CREATED");
              assertThat(history.getRequestPayload())
                  .contains("\"applicationDataVersion\"", "\"requestFingerprint\"")
                  .doesNotContain("LAA-123", "Ada", "Lovelace", "Care order");
              assertThat(history.getServiceName()).isEqualTo("CIVIL_APPLY");
            });

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payload ->> 'laaReference' FROM axon.application_data "
                    + "WHERE application_id = ? AND version = 0",
                String.class,
                applicationId))
        .isEqualTo("LAA-123");

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT payload_type, sequence_number FROM axon.domain_event_entry "
                + "WHERE aggregate_identifier = ?",
            applicationId.toString());
    assertThat(events)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.get("payload_type"))
                  .isEqualTo(
                      "uk.gov.justice.laa.dstew.access.command.application.ApplicationCreatedEvent");
              assertThat(event.get("sequence_number")).isEqualTo(0L);
            });
  }

  @Test
  void givenApplicationInProgress_whenUpdatedToSubmitted_thenPersistsThinEventAndDataAtomically()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    ApplicationCreateRequest submitted =
        validCreateApplicationRequest(applicationId, UUID.randomUUID());
    applicationId(post(inProgress(submitted), headers()));
    awaitProjection(applicationId);

    ResponseEntity<Void> response = patchSubmitted(applicationId, submitted);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ApplicationReadModel projected = awaitProjectionVersion(applicationId, 1L);
    assertThat(projected.getStatus()).isEqualTo("APPLICATION_SUBMITTED");
    assertThat(projected.getAutoGranted()).isEqualTo(AutoGrantedState.PENDING);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.application_data WHERE application_id = ?",
                Integer.class,
                applicationId))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT payload_type, convert_from(payload, 'UTF8') AS payload "
                    + "FROM axon.domain_event_entry "
                    + "WHERE aggregate_identifier = ? AND sequence_number = 1",
                applicationId.toString()))
        .satisfies(
            event -> {
              assertThat(event.get("payload_type").toString()).endsWith("ApplicationUpdatedEvent");
              assertThat(event.get("payload").toString())
                  .contains("applicationDataVersion", "APPLICATION_SUBMITTED")
                  .doesNotContain(
                      "applicationContent", "proceedings", "individuals", "LAA-123", "Ada");
            });
  }

  @Test
  void givenEventAppendFails_whenApplicationUpdated_thenImmutableDataAppendRollsBack()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    ApplicationCreateRequest submitted =
        validCreateApplicationRequest(applicationId, UUID.randomUUID());
    applicationId(post(inProgress(submitted), headers()));
    awaitProjection(applicationId);
    jdbcTemplate.execute(
        """
        CREATE OR REPLACE FUNCTION axon.reject_test_application_update()
        RETURNS trigger AS $$
        BEGIN
          IF NEW.aggregate_identifier = '%s' AND NEW.sequence_number = 1 THEN
            RAISE EXCEPTION 'forced ApplicationUpdatedEvent append failure';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """
            .formatted(applicationId));
    jdbcTemplate.execute(
        """
        CREATE TRIGGER reject_test_application_update
        BEFORE INSERT ON axon.domain_event_entry
        FOR EACH ROW EXECUTE FUNCTION axon.reject_test_application_update()
        """);

    ResponseEntity<String> response;
    try {
      ApplicationUpdateRequest update = submittedUpdate(submitted);
      response =
          restTemplate.exchange(
              "http://localhost:" + port + "/api/v0/applications/" + applicationId,
              HttpMethod.PATCH,
              new HttpEntity<>(update, headers()),
              String.class);
    } finally {
      jdbcTemplate.execute(
          "DROP TRIGGER IF EXISTS reject_test_application_update ON axon.domain_event_entry");
      jdbcTemplate.execute("DROP FUNCTION IF EXISTS axon.reject_test_application_update()");
    }

    assertThat(response.getStatusCode().is5xxServerError()).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.application_data WHERE application_id = ?",
                Integer.class,
                applicationId))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.domain_event_entry WHERE aggregate_identifier = ?",
                Integer.class,
                applicationId.toString()))
        .isEqualTo(1);
    assertThat(awaitProjection(applicationId).getApplicationVersion()).isZero();
  }

  @Test
  void givenApplication_whenMakeDecision_thenAppendsSensitiveVersionAndThinEvent()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, applyProceedingId), headers()));
    ApplicationReadModel created = awaitProjection(applicationId);
    UUID proceedingId = created.getProceedings().getFirst().getId();

    MakeDecisionRequest request =
        MakeDecisionRequest.builder()
            .applicationVersion(0L)
            .overallDecision(DecisionStatus.REFUSED)
            .autoGranted(false)
            .eventHistory(
                EventHistoryRequest.builder().eventDescription("Decision recorded").build())
            .proceedings(
                List.of(
                    MakeDecisionProceedingRequest.builder()
                        .proceedingId(proceedingId)
                        .meritsDecision(
                            MeritsDecisionDetailsRequest.builder()
                                .decision(MeritsDecisionStatus.REFUSED)
                                .reason("Insufficient evidence")
                                .justification("The evidence did not meet the test")
                                .build())
                        .build()))
            .build();

    ResponseEntity<Void> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/applications/" + applicationId + "/decision",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ApplicationReadModel decided = awaitProjectionVersion(applicationId, 1L);
    assertThat(decided.getDecisionStatus()).isEqualTo("REFUSED");
    assertThat(decided.getAutoGranted()).isEqualTo(AutoGrantedState.MANUAL);
    assertThat(decided.getMeritsDecisions().get(proceedingId).justification())
        .isEqualTo("The evidence did not meet the test");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payload ->> 'overallDecision' FROM axon.application_data "
                    + "WHERE application_id = ? AND version = 1",
                String.class,
                applicationId))
        .isEqualTo("REFUSED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT convert_from(payload, 'UTF8') FROM axon.domain_event_entry "
                    + "WHERE aggregate_identifier = ? AND sequence_number = 1",
                String.class,
                applicationId.toString()))
        .contains("applicationDataVersion", "REFUSED")
        .doesNotContain("Insufficient evidence", "The evidence did not meet the test");

    assertThat(
            awaitHistoryTypes(
                applicationId, "APPLICATION_CREATED", "APPLICATION_MAKE_DECISION_REFUSED"))
        .hasSize(2);
    ResponseEntity<ApplicationHistoryResponse> historyResponse =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v0/applications/"
                + applicationId
                + "/history-search?eventType=APPLICATION_MAKE_DECISION_REFUSED",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationHistoryResponse.class);
    assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(historyResponse.getBody().getEvents())
        .singleElement()
        .satisfies(event -> assertThat(event.getEventDescription()).isEqualTo("Decision recorded"));
    ApplicationResponse application = awaitGet(applicationId).getBody();
    assertThat(application.getDecisionStatus()).isEqualTo(DecisionStatus.REFUSED);
    assertThat(application.getAutoGranted()).isEqualTo(AutoGranted.MANUAL);
    assertThat(application.getVersion()).isEqualTo(1L);
    assertThat(application.getProceedings().getFirst().getMeritsDecision())
        .isEqualTo(MeritsDecisionStatus.REFUSED);

    ResponseEntity<Void> staleResponse =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/applications/" + applicationId + "/decision",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);
    assertThat(staleResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.application_data WHERE application_id = ?",
                Integer.class,
                applicationId))
        .isEqualTo(2);
  }

  @Test
  void givenSubmittedApplication_whenAutomaticallyGranted_thenPersistsCompleteDecision()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()));
    UUID proceedingId = awaitProjection(applicationId).getProceedings().getFirst().getId();
    var request =
        new AutoGrantedOutcomeRequest(
            AutoGrantOutcome.AUTOGRANTED, Map.of("certificateNumber", "AUTO-2126"));

    ResponseEntity<Void> response =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v0/applications/"
                + applicationId
                + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ApplicationReadModel granted = awaitProjectionVersion(applicationId, 1L);
    assertThat(granted.getAutoGranted()).isEqualTo(AutoGrantedState.AUTOGRANTED);
    assertThat(granted.getDecisionStatus()).isEqualTo("GRANTED");
    assertThat(granted.getMeritsDecisions()).containsKey(proceedingId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payload -> 'certificate' ->> 'certificateNumber' "
                    + "FROM axon.application_data WHERE application_id = ? AND version = 1",
                String.class,
                applicationId))
        .isEqualTo("AUTO-2126");
  }

  @Test
  void givenSubmittedApplication_whenMarkedReady_thenPersistsManualOutcomeAndThinEvent()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()));
    awaitProjection(applicationId);
    ManualOutcomeRequest request = new ManualOutcomeRequest(AutoGrantOutcome.MANUAL);

    ResponseEntity<Void> response =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v0/applications/"
                + applicationId
                + "/auto-grant-outcome",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ApplicationReadModel ready = awaitProjectionVersion(applicationId, 1L);
    assertThat(ready.getStatus()).isEqualTo("APPLICATION_SUBMITTED");
    assertThat(ready.getAutoGranted()).isEqualTo(AutoGrantedState.MANUAL);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payload ->> 'autoGranted' FROM axon.application_data "
                    + "WHERE application_id = ? AND version = 1",
                String.class,
                applicationId))
        .isEqualTo("MANUAL");
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT payload_type, convert_from(payload, 'UTF8') AS payload "
                    + "FROM axon.domain_event_entry "
                    + "WHERE aggregate_identifier = ? AND sequence_number = 1",
                applicationId.toString()))
        .satisfies(
            event -> {
              assertThat(event.get("payload_type").toString())
                  .endsWith("ApplicationReadyForManualAssessmentEvent");
              assertThat(event.get("payload").toString())
                  .contains("applicationDataVersion")
                  .doesNotContain("applicationContent", "LAA-123");
            });
  }

  @Test
  void givenApplicationCertificate_whenGetCertificate_thenReturnsCurrentCertificate()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, applyProceedingId), headers()));
    UUID proceedingId = awaitProjection(applicationId).getProceedings().getFirst().getId();
    Map<String, Object> certificate =
        Map.of(
            "certificateNumber", "TESTCERT001",
            "issueDate", "2026-03-03",
            "validUntil", "2027-03-03");
    MakeDecisionRequest request =
        MakeDecisionRequest.builder()
            .applicationVersion(0L)
            .overallDecision(DecisionStatus.GRANTED)
            .autoGranted(false)
            .certificate(certificate)
            .eventHistory(
                EventHistoryRequest.builder().eventDescription("Certificate granted").build())
            .proceedings(
                List.of(
                    MakeDecisionProceedingRequest.builder()
                        .proceedingId(proceedingId)
                        .meritsDecision(
                            MeritsDecisionDetailsRequest.builder()
                                .decision(MeritsDecisionStatus.GRANTED)
                                .justification("Decision approved")
                                .build())
                        .build()))
            .build();

    ResponseEntity<Void> decisionResponse =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/applications/" + applicationId + "/decision",
            HttpMethod.PATCH,
            new HttpEntity<>(request, headers()),
            Void.class);
    assertThat(decisionResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    awaitProjectionVersion(applicationId, 1L);

    ResponseEntity<Map<String, Object>> certificateResponse =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/applications/" + applicationId + "/certificate",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            new ParameterizedTypeReference<>() {});

    assertThat(certificateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(certificateResponse.getBody()).containsAllEntriesOf(certificate);

    UUID applicationWithoutCertificate = UUID.randomUUID();
    applicationId(
        post(
            validCreateApplicationRequest(applicationWithoutCertificate, UUID.randomUUID()),
            headers()));
    awaitProjection(applicationWithoutCertificate);
    ResponseEntity<String> missingCertificateResponse =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v0/applications/"
                + applicationWithoutCertificate
                + "/certificate",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            String.class);
    assertThat(missingCertificateResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(missingCertificateResponse.getBody())
        .contains("No certificate found for application id: " + applicationWithoutCertificate);

    UUID missingApplicationId = UUID.randomUUID();
    ResponseEntity<String> missingApplicationResponse =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v0/applications/"
                + missingApplicationId
                + "/certificate",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            String.class);
    assertThat(missingApplicationResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(missingApplicationResponse.getBody())
        .contains("No application found with id: " + missingApplicationId);
  }

  @Test
  void givenKnownCaseworkerAndApplication_whenAssigned_thenUpdatesOnlyRequestedApplication()
      throws Exception {
    UUID caseworkerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO axon.caseworkers (id, username) VALUES (?, ?)",
        caseworkerId,
        "caseworker@example.com");
    UUID firstApplicationId = UUID.randomUUID();
    UUID secondApplicationId = UUID.randomUUID();
    applicationId(
        post(validCreateApplicationRequest(firstApplicationId, UUID.randomUUID()), headers()));
    applicationId(
        post(validCreateApplicationRequest(secondApplicationId, UUID.randomUUID()), headers()));
    awaitProjection(firstApplicationId);
    awaitProjection(secondApplicationId);

    CaseworkerAssignRequest request =
        CaseworkerAssignRequest.builder()
            .caseworkerId(caseworkerId)
            .applicationIds(List.of(firstApplicationId))
            .eventHistory(
                EventHistoryRequest.builder().eventDescription("Assigned for assessment").build())
            .build();
    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications/assign",
            new HttpEntity<>(request, headers()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(awaitProjectionVersion(firstApplicationId, 1L).getCaseworkerId())
        .isEqualTo(caseworkerId);
    assertThat(awaitProjection(secondApplicationId).getCaseworkerId()).isNull();
    assertThat(awaitGet(firstApplicationId).getBody().getAssignedTo()).isEqualTo(caseworkerId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT convert_from(payload, 'UTF8') FROM axon.domain_event_entry "
                    + "WHERE aggregate_identifier = ? AND sequence_number = 1",
                String.class,
                firstApplicationId.toString()))
        .contains("caseworkerId", caseworkerId.toString())
        .doesNotContain("Assigned for assessment");

    ResponseEntity<ApplicationHistoryResponse> historyResponse =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v0/applications/"
                + firstApplicationId
                + "/history-search?eventType=ASSIGN_APPLICATION_TO_CASEWORKER",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationHistoryResponse.class);
    assertThat(historyResponse.getBody().getEvents())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getCaseworkerId()).isEqualTo(caseworkerId);
              assertThat(event.getEventDescription()).isEqualTo("Assigned for assessment");
            });

    CaseworkerUnassignRequest unassignRequest =
        CaseworkerUnassignRequest.builder()
            .eventHistory(
                EventHistoryRequest.builder().eventDescription("Returned to queue").build())
            .build();
    ResponseEntity<Void> unassignResponse =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications/" + firstApplicationId + "/unassign",
            new HttpEntity<>(unassignRequest, headers()),
            Void.class);
    assertThat(unassignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(awaitProjectionVersion(firstApplicationId, 2L).getCaseworkerId()).isNull();
    assertThat(awaitGet(firstApplicationId).getBody().getAssignedTo()).isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT convert_from(payload, 'UTF8') FROM axon.domain_event_entry "
                    + "WHERE aggregate_identifier = ? AND sequence_number = 2",
                String.class,
                firstApplicationId.toString()))
        .doesNotContain("Returned to queue");

    ResponseEntity<ApplicationHistoryResponse> unassignHistoryResponse =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v0/applications/"
                + firstApplicationId
                + "/history-search?eventType=UNASSIGN_APPLICATION_TO_CASEWORKER",
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            ApplicationHistoryResponse.class);
    assertThat(unassignHistoryResponse.getBody().getEvents())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getCaseworkerId()).isNull();
              assertThat(event.getEventDescription()).isEqualTo("Returned to queue");
            });

    ResponseEntity<Void> repeatedUnassignResponse =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications/" + firstApplicationId + "/unassign",
            new HttpEntity<>(unassignRequest, headers()),
            Void.class);
    assertThat(repeatedUnassignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(awaitProjection(firstApplicationId).getApplicationVersion()).isEqualTo(2L);

    CaseworkerAssignRequest multipleApplicationsRequest =
        CaseworkerAssignRequest.builder()
            .caseworkerId(caseworkerId)
            .applicationIds(List.of(firstApplicationId, secondApplicationId))
            .build();
    ResponseEntity<Void> listResponse =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications/assign",
            new HttpEntity<>(multipleApplicationsRequest, headers()),
            Void.class);
    assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    CaseworkerAssignRequest missingApplicationRequest =
        CaseworkerAssignRequest.builder()
            .caseworkerId(caseworkerId)
            .applicationIds(List.of(UUID.randomUUID()))
            .build();
    ResponseEntity<Void> missingResponse =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications/assign",
            new HttpEntity<>(missingApplicationRequest, headers()),
            Void.class);
    assertThat(missingResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Void> missingUnassignResponse =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications/" + UUID.randomUUID() + "/unassign",
            new HttpEntity<>(unassignRequest, headers()),
            Void.class);
    assertThat(missingUnassignResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(awaitProjection(secondApplicationId).getApplicationVersion()).isZero();
  }

  @Test
  void givenCreatedApplication_whenGetApplication_thenReturnsCurrentStateProjection()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    final UUID involvedChildId = UUID.randomUUID();
    ApplicationCreateRequest request =
        validCreateApplicationRequest(applicationId, applyProceedingId);
    Map<String, Object> content = new HashMap<>(request.getApplicationContent());
    Map<String, Object> proceeding = firstProceeding(content);
    proceeding.put("meaning", "Care proceedings");
    proceeding.put("substantiveLevelOfServiceName", "FULL_REPRESENTATION");
    proceeding.put("substantiveCostLimitation", "2500.0");
    proceeding.put(
        "involvedChildren",
        List.of(
            Map.of(
                "id",
                involvedChildId.toString(),
                "fullName",
                "Child Example",
                "dateOfBirth",
                "2015-01-02")));
    proceeding.put(
        "scopeLimitations",
        List.of(
            Map.ofEntries(
                Map.entry("id", UUID.randomUUID().toString()),
                Map.entry("type", "LIMITATION"),
                Map.entry("code", "CV117"),
                Map.entry("meaning", "LIMITED"),
                Map.entry("description", "Limited scope"))));
    content.put("proceedings", List.of(proceeding));
    content.put(
        "opponents",
        List.of(Map.of("opponentType", "INDIVIDUAL", "firstName", "Grace", "lastName", "Hopper")));
    request.setApplicationContent(content);

    UUID createdApplicationId = applicationId(post(request, headers()));
    ResponseEntity<ApplicationResponse> response = awaitGet(createdApplicationId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ApplicationResponse actual = response.getBody();
    assertThat(actual).isNotNull();
    assertThat(actual.getLastUpdated()).isNotNull();
    assertThat(actual.getProceedings())
        .singleElement()
        .satisfies(
            proceedingResponse -> assertThat(proceedingResponse.getProceedingId()).isNotNull());

    ApplicationResponse expected =
        new ApplicationResponse()
            .applicationId(createdApplicationId)
            .status(ApplicationStatus.APPLICATION_SUBMITTED)
            .laaReference("LAA-123")
            .lastUpdated(actual.getLastUpdated())
            .submittedAt(OffsetDateTime.parse("2026-07-14T12:30:00Z"))
            .isLead(false)
            .usedDelegatedFunctions(false)
            .autoGranted(AutoGranted.PENDING)
            .version(0L)
            .provider(
                ProviderResponse.builder()
                    .officeCode("1A001B")
                    .contactEmail("provider@example.com")
                    .build())
            .opponents(
                List.of(
                    new OpponentResponse()
                        .opponentType("INDIVIDUAL")
                        .firstName("Grace")
                        .lastName("Hopper")))
            .proceedings(
                List.of(
                    new ApplicationProceedingResponse()
                        .proceedingId(actual.getProceedings().getFirst().getProceedingId())
                        .proceedingDescription("Care order")
                        .proceedingType("Care proceedings")
                        .categoryOfLaw(CategoryOfLaw.FAMILY)
                        .matterType(MatterType.SPECIAL_CHILDREN_ACT)
                        .levelOfService("FULL_REPRESENTATION")
                        .substantiveCostLimitation(2_500.0)
                        .scopeLimitations(
                            List.of(
                                new ScopeLimitationResponse()
                                    .scopeLimitation("LIMITED")
                                    .scopeDescription("Limited scope")))
                        .involvedChildren(
                            List.of(
                                new InvolvedChildResponse()
                                    .fullName("Child Example")
                                    .dateOfBirth(LocalDate.of(2015, 1, 2))))));

    assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
  }

  @Test
  void givenUnknownApplication_whenGetApplication_thenReturnsNotFound() {
    UUID applicationId = UUID.randomUUID();

    ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + port + "/api/v0/applications/" + applicationId, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains("No application found with ID: " + applicationId);
  }

  @Test
  void givenIdenticalRetry_whenPostApplicationAgain_thenReturnsCreatedIdempotently()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    HttpEntity<ApplicationCreateRequest> request =
        new HttpEntity<>(
            validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers());

    ResponseEntity<Void> firstResponse =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications", request, Void.class);
    awaitProjection(applicationId(firstResponse));

    ResponseEntity<Void> retryResponse =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v0/applications", request, Void.class);

    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retryResponse.getHeaders().getLocation())
        .isEqualTo(firstResponse.getHeaders().getLocation());

    UUID createdApplicationId = applicationId(firstResponse);
    assertThat(awaitHistory(createdApplicationId, 1))
        .singleElement()
        .satisfies(h -> assertThat(h.getEventType()).isEqualTo("APPLICATION_CREATED"));

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT COUNT(*) as cnt FROM axon.domain_event_entry WHERE aggregate_identifier = ?",
            createdApplicationId.toString());
    assertThat(events.getFirst().get("cnt")).isEqualTo(1L);
  }

  @Test
  void givenChangedPayload_whenPostApplicationAgain_thenReturnsConflict() throws Exception {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();

    ResponseEntity<Void> firstResponse =
        post(validCreateApplicationRequest(applicationId, applyProceedingId), headers());
    awaitProjection(applicationId(firstResponse));

    ResponseEntity<String> conflictResponse =
        post(
            validCreateApplicationRequest(applicationId, UUID.randomUUID()),
            headers(),
            String.class);

    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(awaitHistory(applicationId, 1)).hasSize(1);
  }

  @Test
  @Disabled("Linked applications removed from schema; orchestration retained for future endpoint")
  void givenExistingLeadApplication_whenPostLinkedApplication_thenProjectsCurrentStateAndHistory()
      throws Exception {
    UUID leadApplicationId = UUID.randomUUID();
    ResponseEntity<Void> leadResponse =
        post(validCreateApplicationRequest(leadApplicationId, UUID.randomUUID()), headers());
    UUID createdLeadApplicationId = applicationId(leadResponse);
    awaitProjection(createdLeadApplicationId);

    ResponseEntity<Void> linkedResponse =
        post(
            validLinkedCreateApplicationRequest(
                UUID.randomUUID(), UUID.randomUUID(), createdLeadApplicationId),
            headers());
    UUID linkedApplicationId = applicationId(linkedResponse);

    assertThat(
            awaitHistoryTypes(
                linkedApplicationId, "APPLICATION_CREATED", "APPLICATION_GROUP_JOINED"))
        .extracting(ApplicationHistoryReadModel::getEventType)
        .containsExactlyInAnyOrder("APPLICATION_CREATED", "APPLICATION_GROUP_JOINED");
    assertThat(
            awaitHistoryTypes(
                createdLeadApplicationId, "APPLICATION_CREATED", "APPLICATION_GROUP_CREATED"))
        .extracting(ApplicationHistoryReadModel::getEventType)
        .containsExactlyInAnyOrder("APPLICATION_CREATED", "APPLICATION_GROUP_CREATED");
    ApplicationReadModel projected =
        applicationReadRepository
            .findById(linkedApplicationId)
            .orElseThrow(() -> new AssertionError("Application not found: " + linkedApplicationId));
    assertThat(projected.getLeadApplicationId()).isEqualTo(createdLeadApplicationId);
  }

  @Test
  @Disabled("Linked applications removed from schema; orchestration retained for future endpoint")
  void givenMissingLeadApplication_whenPostApplication_thenReturnsNotFound() {
    UUID missingLeadApplicationId = UUID.randomUUID();
    UUID rejectedApplicationId = UUID.randomUUID();

    ResponseEntity<String> response =
        post(
            validLinkedCreateApplicationRequest(
                rejectedApplicationId, UUID.randomUUID(), missingLeadApplicationId),
            headers(),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains(missingLeadApplicationId.toString());
    assertRejectedApplicationWasRolledBack(rejectedApplicationId);
    assertThat(groupReadRepository.findByLeadApplicationId(missingLeadApplicationId)).isEmpty();
  }

  @Test
  @Disabled("Linked applications removed from schema; orchestration retained for future endpoint")
  void givenMissingAssociatedApplication_whenPostApplication_thenReturnsNotFound()
      throws Exception {
    UUID leadApplicationId = UUID.randomUUID();
    UUID createdLeadApplicationId =
        applicationId(
            post(validCreateApplicationRequest(leadApplicationId, UUID.randomUUID()), headers()));
    awaitProjection(createdLeadApplicationId);

    UUID missingAssociatedApplicationId = UUID.randomUUID();
    UUID rejectedApplicationId = UUID.randomUUID();
    ResponseEntity<String> response =
        post(
            validLinkedCreateApplicationRequest(
                rejectedApplicationId,
                UUID.randomUUID(),
                createdLeadApplicationId,
                missingAssociatedApplicationId),
            headers(),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains(missingAssociatedApplicationId.toString());
    assertRejectedApplicationWasRolledBack(rejectedApplicationId);
    assertThat(groupReadRepository.findByLeadApplicationId(createdLeadApplicationId)).isEmpty();
  }

  @Test
  void givenNullContentId_whenPostApplication_thenReturnsBadRequestWithNoEvent() {
    ApplicationCreateRequest request =
        validCreateApplicationRequest(UUID.randomUUID(), UUID.randomUUID());
    Map<String, Object> contentWithNullId = new HashMap<>(request.getApplicationContent());
    contentWithNullId.put("id", null);
    request.setApplicationContent(contentWithNullId);

    ResponseEntity<String> response = post(request, headers(), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("Generic Validation Error");
  }

  @Test
  void givenSchemaInvalidRequest_whenPostApplication_thenReturnsBadRequest() throws Exception {
    ApplicationCreateRequest request =
        validCreateApplicationRequest(UUID.randomUUID(), UUID.randomUUID());
    Map<String, Object> invalidContent = new HashMap<>(request.getApplicationContent());
    invalidContent.remove("submittedAt");
    request.setApplicationContent(invalidContent);

    ResponseEntity<String> response = post(request, headers(), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("Generic Validation Error");
  }

  @Test
  void givenContentWithoutLeadProceeding_whenPostApplication_thenReturnsBadRequest() {
    ApplicationCreateRequest request =
        validCreateApplicationRequest(UUID.randomUUID(), UUID.randomUUID());
    Map<String, Object> content = new HashMap<>(request.getApplicationContent());
    Map<String, Object> proceeding = firstProceeding(content);
    proceeding.put("leadProceeding", false);
    content.put("proceedings", List.of(proceeding));
    request.setApplicationContent(content);

    ResponseEntity<String> response = post(request, headers(1), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("No lead proceeding found in application content");
  }

  @Test
  void givenUnparseableSubmissionTimestamp_whenPostApplication_thenReturnsBadRequest() {
    ApplicationCreateRequest request =
        validCreateApplicationRequest(UUID.randomUUID(), UUID.randomUUID());
    Map<String, Object> content = new HashMap<>(request.getApplicationContent());
    content.put("submittedAt", "not-an-instant");
    request.setApplicationContent(content);

    ResponseEntity<String> response = post(request, headers(), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .contains("submittedAt")
        .contains("must be a valid RFC 3339 date-time");
  }

  @Test
  void givenInvalidContentType_whenPostApplication_thenReturnsBadRequest() {
    ApplicationCreateRequest request =
        validCreateApplicationRequest(UUID.randomUUID(), UUID.randomUUID());
    Map<String, Object> content = new HashMap<>(request.getApplicationContent());
    Map<String, Object> proceeding = firstProceeding(content);
    proceeding.put("substantiveCostLimitation", "not-a-number");
    content.put("proceedings", List.of(proceeding));
    request.setApplicationContent(content);

    ResponseEntity<String> response = post(request, headers(), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("substantiveCostLimitation");
  }

  @Test
  void givenConcurrentIdenticalRequests_whenPosted_thenBothSucceedWithOneCreationEvent()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    UUID applyProceedingId = UUID.randomUUID();
    ApplicationCreateRequest request =
        validCreateApplicationRequest(applicationId, applyProceedingId);
    HttpEntity<ApplicationCreateRequest> entity = new HttpEntity<>(request, headers());

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<ResponseEntity<Void>> f1 =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  barrier.await(10, TimeUnit.SECONDS);
                  return restTemplate.postForEntity(
                      "http://localhost:" + port + "/api/v0/applications", entity, Void.class);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              },
              executor);
      CompletableFuture<ResponseEntity<Void>> f2 =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  barrier.await(10, TimeUnit.SECONDS);
                  return restTemplate.postForEntity(
                      "http://localhost:" + port + "/api/v0/applications", entity, Void.class);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              },
              executor);

      ResponseEntity<Void> r1 = f1.get(20, TimeUnit.SECONDS);
      ResponseEntity<Void> r2 = f2.get(20, TimeUnit.SECONDS);

      // Both requests must resolve successfully regardless of which wins the concurrency race.
      assertThat(r1.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(r2.getStatusCode().is2xxSuccessful()).isTrue();
    } finally {
      executor.shutdown();
    }

    // The event store must contain exactly one ApplicationCreatedEvent.
    awaitProjection(applicationId);
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT payload_type, sequence_number FROM axon.domain_event_entry "
                + "WHERE aggregate_identifier = ? ORDER BY sequence_number",
            applicationId.toString());
    assertThat(events)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.get("payload_type")).asString().contains("ApplicationCreatedEvent");
              assertThat(event.get("sequence_number")).isEqualTo(0L);
            });
  }

  @Test
  void givenConcurrentDecisionsAtSameVersion_whenPatched_thenOnlyOneDecisionIsCommitted()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()));
    UUID proceedingId = awaitProjection(applicationId).getProceedings().getFirst().getId();
    MakeDecisionRequest request =
        MakeDecisionRequest.builder()
            .applicationVersion(0L)
            .overallDecision(DecisionStatus.REFUSED)
            .autoGranted(false)
            .eventHistory(EventHistoryRequest.builder().eventDescription("Concurrent").build())
            .proceedings(
                List.of(
                    MakeDecisionProceedingRequest.builder()
                        .proceedingId(proceedingId)
                        .meritsDecision(
                            MeritsDecisionDetailsRequest.builder()
                                .decision(MeritsDecisionStatus.REFUSED)
                                .reason("Insufficient evidence")
                                .justification("Concurrent decision")
                                .build())
                        .build()))
            .build();
    HttpEntity<MakeDecisionRequest> entity = new HttpEntity<>(request, headers());
    String url = "http://localhost:" + port + "/api/v0/applications/" + applicationId + "/decision";
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<ResponseEntity<Void>> first =
          concurrentPatch(executor, barrier, url, entity);
      CompletableFuture<ResponseEntity<Void>> second =
          concurrentPatch(executor, barrier, url, entity);

      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .extracting(ResponseEntity::getStatusCode)
          .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.CONFLICT);
    } finally {
      executor.shutdown();
    }

    assertThat(awaitProjectionVersion(applicationId, 1L).getApplicationVersion()).isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.application_data WHERE application_id = ?",
                Integer.class,
                applicationId))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.domain_event_entry WHERE aggregate_identifier = ?",
                Integer.class,
                applicationId.toString()))
        .isEqualTo(2);
  }

  @Test
  void givenConcurrentManualReadinessAtSameVersion_whenPatched_thenOnlyOneOutcomeIsCommitted()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()));
    awaitProjection(applicationId);
    ManualOutcomeRequest request = new ManualOutcomeRequest(AutoGrantOutcome.MANUAL);
    HttpEntity<ManualOutcomeRequest> entity = new HttpEntity<>(request, headers());
    String url =
        "http://localhost:"
            + port
            + "/api/v0/applications/"
            + applicationId
            + "/auto-grant-outcome";
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<ResponseEntity<Void>> first =
          concurrentPatch(executor, barrier, url, entity);
      CompletableFuture<ResponseEntity<Void>> second =
          concurrentPatch(executor, barrier, url, entity);

      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .extracting(ResponseEntity::getStatusCode)
          .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.OK);
    } finally {
      executor.shutdown();
    }

    assertThat(awaitProjectionVersion(applicationId, 1L).getAutoGranted())
        .isEqualTo(AutoGrantedState.MANUAL);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.application_data WHERE application_id = ?",
                Integer.class,
                applicationId))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axon.domain_event_entry WHERE aggregate_identifier = ?",
                Integer.class,
                applicationId.toString()))
        .isEqualTo(2);
  }

  private <T> CompletableFuture<ResponseEntity<Void>> concurrentPatch(
      ExecutorService executor, CyclicBarrier barrier, String url, HttpEntity<T> entity) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            barrier.await(10, TimeUnit.SECONDS);
            return restTemplate.exchange(url, HttpMethod.PATCH, entity, Void.class);
          } catch (Exception exception) {
            throw new RuntimeException(exception);
          }
        },
        executor);
  }

  @Test
  void givenSeededCaseworkers_whenGetCaseworkers_thenReturnsAllCaseworkers() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO axon.caseworkers (id, username) VALUES (?, ?)", firstId, "alice@example.com");
    jdbcTemplate.update(
        "INSERT INTO axon.caseworkers (id, username) VALUES (?, ?)", secondId, "bob@example.com");

    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    ResponseEntity<List<Map<String, Object>>> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/caseworkers",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .extracting(item -> item.get("username"))
        .contains("alice@example.com", "bob@example.com");
  }

  @Test
  void givenMissingServiceNameHeader_whenGetCaseworkers_thenReturnsBadRequest() {
    ResponseEntity<String> response =
        restTemplate.getForEntity("http://localhost:" + port + "/api/v0/caseworkers", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private ResponseEntity<Void> post(ApplicationCreateRequest request, HttpHeaders headers) {
    return restTemplate.postForEntity(
        "http://localhost:" + port + "/api/v0/applications",
        new HttpEntity<>(request, headers),
        Void.class);
  }

  private ResponseEntity<String> post(
      ApplicationCreateRequest request, HttpHeaders headers, Class<String> responseType) {
    return restTemplate.postForEntity(
        "http://localhost:" + port + "/api/v0/applications",
        new HttpEntity<>(request, headers),
        responseType);
  }

  private ApplicationCreateRequest inProgress(ApplicationCreateRequest submitted) {
    return submitted.toBuilder().status(ApplicationStatus.APPLICATION_IN_PROGRESS).build();
  }

  private ApplicationUpdateRequest submittedUpdate(ApplicationCreateRequest submitted) {
    return new ApplicationUpdateRequest()
        .status(ApplicationStatus.APPLICATION_SUBMITTED)
        .applicationContent(submitted.getApplicationContent());
  }

  private ResponseEntity<Void> patchSubmitted(
      UUID applicationId, ApplicationCreateRequest submitted) {
    return restTemplate.exchange(
        "http://localhost:" + port + "/api/v0/applications/" + applicationId,
        HttpMethod.PATCH,
        new HttpEntity<>(submittedUpdate(submitted), headers()),
        Void.class);
  }

  @Test
  void givenExistingApplication_whenCreateNote_thenReturns204AndPersistsNoteInApplicationData()
      throws Exception {
    UUID applicationId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()));
    awaitProjection(applicationId);

    ResponseEntity<Void> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/applications/" + applicationId + "/notes",
            HttpMethod.POST,
            new HttpEntity<>(new CreateNoteRequest("Integration test note"), headers()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    // applicationDataVersion advances to 1; applicationVersion stays at 0
    ApplicationReadModel model = awaitProjectionVersion(applicationId, 1L);
    assertThat(model.getApplicationVersion()).isEqualTo(0L);

    // Note text persisted in application_data JSONB at version 1
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payload -> 'notes' -> 0 ->> 'noteText' FROM axon.application_data"
                    + " WHERE application_id = ? AND version = 1",
                String.class,
                applicationId))
        .isEqualTo("Integration test note");

    // NoteCreatedEvent is thin — note text must not appear in the event stream
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT convert_from(payload, 'UTF8') FROM axon.domain_event_entry"
                    + " WHERE aggregate_identifier = ? AND sequence_number = 1",
                String.class,
                applicationId.toString()))
        .contains("applicationDataVersion")
        .doesNotContain("Integration test note");

    awaitHistoryTypes(applicationId, "APPLICATION_CREATED", "APPLICATION_NOTE_CREATED");
  }

  @Test
  void givenNoApplication_whenCreateNote_thenReturns404() {
    ResponseEntity<Void> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/v0/applications/" + UUID.randomUUID() + "/notes",
            HttpMethod.POST,
            new HttpEntity<>(new CreateNoteRequest("Should fail"), headers()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void givenApplicationWithNote_whenGetNotes_thenReturnsNoteInResponse() throws Exception {
    UUID applicationId = UUID.randomUUID();
    applicationId(post(validCreateApplicationRequest(applicationId, UUID.randomUUID()), headers()));
    awaitProjection(applicationId);

    restTemplate.exchange(
        "http://localhost:" + port + "/api/v0/applications/" + applicationId + "/notes",
        HttpMethod.POST,
        new HttpEntity<>(new CreateNoteRequest("Hello from GET notes test"), headers()),
        Void.class);
    awaitProjectionVersion(applicationId, 1L);

    ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + port + "/api/v0/applications/" + applicationId + "/notes",
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("Hello from GET notes test");
  }

  @Test
  void givenNoApplication_whenGetNotes_thenReturns404() {
    ResponseEntity<Void> response =
        restTemplate.getForEntity(
            "http://localhost:" + port + "/api/v0/applications/" + UUID.randomUUID() + "/notes",
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private HttpHeaders headers() {
    return headers(1);
  }

  private HttpHeaders headers(int schemaVersion) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Service-Name", "CIVIL_APPLY");
    headers.set("X-Schema-Version", String.valueOf(schemaVersion));
    return headers;
  }

  private UUID applicationId(ResponseEntity<Void> response) {
    assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getLocation()).isNotNull();
    return UUID.fromString(
        response.getHeaders().getLocation().getPath().replace("/api/v0/applications/", ""));
  }

  private Map<String, Object> firstProceeding(Map<String, Object> applicationContent) {
    Map<?, ?> source = (Map<?, ?>) ((List<?>) applicationContent.get("proceedings")).getFirst();
    Map<String, Object> proceeding = new HashMap<>();
    source.forEach((key, value) -> proceeding.put(key.toString(), value));
    return proceeding;
  }

  private ResponseEntity<ApplicationResponse> awaitGet(UUID applicationId) throws Exception {
    ResponseEntity<String> response =
        await()
            .alias("application to be available from the query projection: " + applicationId)
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(
                () ->
                    restTemplate.getForEntity(
                        "http://localhost:" + port + "/api/v0/applications/" + applicationId,
                        String.class),
                candidate -> candidate.getStatusCode() == HttpStatus.OK);
    return new ResponseEntity<>(
        objectMapper.readValue(response.getBody(), ApplicationResponse.class),
        response.getHeaders(),
        response.getStatusCode());
  }

  private ApplicationReadModel awaitProjection(UUID applicationId) {
    return await()
        .alias("application projection to be populated for " + applicationId)
        .atMost(15, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .until(
            () ->
                queryGateway
                    .query(new FindApplicationByIdQuery(applicationId), ApplicationReadModel.class)
                    .join(),
            java.util.Objects::nonNull);
  }

  private ApplicationReadModel awaitProjectionVersion(UUID applicationId, long version) {
    return await()
        .alias("application projection to reach version " + version + " for " + applicationId)
        .atMost(15, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .until(
            () ->
                queryGateway
                    .query(new FindApplicationByIdQuery(applicationId), ApplicationReadModel.class)
                    .join(),
            projected -> projected != null && projected.getApplicationDataVersion() == version);
  }

  private java.util.List<
          uk.gov.justice.laa.dstew.access.query.application.history.ApplicationHistoryReadModel>
      awaitHistory(UUID applicationId, int expectedCount) {
    return await()
        .alias(
            "application history projection to contain "
                + expectedCount
                + " events for "
                + applicationId)
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
        .alias("application history projection to contain " + expected + " for " + applicationId)
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(50, TimeUnit.MILLISECONDS)
        .until(
            () ->
                applicationHistoryReadRepository.findAllByApplicationIdOrderByOccurredAtAsc(
                    applicationId),
            history -> {
              List<String> actual =
                  history.stream().map(ApplicationHistoryReadModel::getEventType).toList();
              return actual.size() == expected.size()
                  && new java.util.HashSet<>(actual).equals(new java.util.HashSet<>(expected));
            });
  }

  private void assertRejectedApplicationWasRolledBack(UUID applicationId) {
    Integer eventCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM axon.domain_event_entry WHERE aggregate_identifier = ?",
            Integer.class,
            applicationId.toString());
    assertThat(eventCount).isZero();
    assertThat(applicationReadRepository.findById(applicationId)).isEmpty();
    assertThat(applicationHistoryReadRepository.countByApplicationId(applicationId)).isZero();
  }
}
