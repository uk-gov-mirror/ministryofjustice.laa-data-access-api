package uk.gov.justice.laa.dstew.access.command.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityType.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import uk.gov.justice.laa.dstew.access.command.application.data.ApplicationDataStore;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataStore;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDraftStore;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.decision.ApportionmentInformation;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.decision.MakePriorAuthorityDecisionCommand;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.decision.PriorAuthorityDecisionMadeEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.document.UploadedDocumentStore;
import uk.gov.justice.laa.dstew.access.command.worklist.WorkItemAssigned;
import uk.gov.justice.laa.dstew.access.command.worklist.WorkItemType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityDocument;
import uk.gov.justice.laa.dstew.access.exception.PriorAuthorityCreationConflictException;
import uk.gov.justice.laa.dstew.access.exception.PriorAuthorityStatusConflictException;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;
import uk.gov.justice.laa.dstew.access.testsupport.TestJwtDecoderConfig;
import uk.gov.justice.laa.dstew.access.util.PayloadFingerprint;
import uk.gov.justice.laa.dstew.access.validation.JsonSchemaValidator;
import uk.gov.justice.laa.dstew.access.validation.ValidationException;

/** Integration tests for {@link PriorAuthorityAggregate} using the Axon test fixture. */
@ExtendWith(MockitoExtension.class)
class PriorAuthorityAggregateTest {

  private AxonTestFixture fixture;
  @Mock private PriorAuthorityDataStore dataStore;
  @Mock private PriorAuthorityDraftStore draftStore;
  @Mock private UploadedDocumentStore uploadedDocumentStore;
  @Mock private ApplicationDataStore applicationDataStore;
  @Mock private JsonSchemaValidator jsonSchemaValidator;
  @Mock private EventAppender eventAppender;

  @BeforeEach
  void setUp() {
    fixture =
        AxonTestFixture.with(
            EventSourcingConfigurer.create()
                .registerEntity(
                    EventSourcedEntityModule.autodetected(
                        UUID.class, PriorAuthorityAggregate.class))
                .componentRegistry(
                    registry ->
                        registry
                            .registerComponent(
                                PriorAuthorityDataStore.class, configuration -> dataStore)
                            .registerComponent(
                                PriorAuthorityDraftStore.class, configuration -> draftStore)
                            .registerComponent(
                                UploadedDocumentStore.class, configuration -> uploadedDocumentStore)
                            .registerComponent(
                                ApplicationDataStore.class, configuration -> applicationDataStore)
                            .registerComponent(
                                JsonSchemaValidator.class, configuration -> jsonSchemaValidator)));
  }

  @AfterEach
  void tearDown() {
    fixture.stop();
  }

  @Test
  void givenNewAggregate_whenCreateDraft_thenWritesDraftAndEmitsDraftStartedEvent() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    PriorAuthorityContent content = new PriorAuthorityContent(null, null, null, null, null);
    String serialisedRequest = "{}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);

    when(draftStore.upsert(
            eq(priorAuthorityId), eq(applicationId), any(), eq(serialisedRequest), eq(occurredAt)))
        .thenReturn(fingerprint);

    CreatePriorAuthorityDraftCommand command =
        new CreatePriorAuthorityDraftCommand(
            priorAuthorityId,
            applicationId,
            content,
            serialisedRequest,
            1,
            "PriorAuthority.json",
            occurredAt);

    fixture
        .given()
        .noPriorActivity()
        .when()
        .command(command)
        .then()
        .events(
            new PriorAuthorityDraftStartedEvent(
                priorAuthorityId, applicationId, null, 1, occurredAt));

    ArgumentCaptor<PriorAuthorityDataPayload> payloadCaptor =
        ArgumentCaptor.forClass(PriorAuthorityDataPayload.class);
    verify(draftStore)
        .upsert(
            eq(priorAuthorityId),
            eq(applicationId),
            payloadCaptor.capture(),
            eq(serialisedRequest),
            eq(occurredAt));
    PriorAuthorityDataPayload persisted = payloadCaptor.getValue();
    assertThat(persisted.priorAuthorityId()).isEqualTo(priorAuthorityId);
    assertThat(persisted.applicationId()).isEqualTo(applicationId);
  }

  @Test
  void givenDraftInProgress_whenUpdateDraft_thenPersistsDraftAndEmitsDraftUpdatedEvent() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    String firstRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String secondRequest = "{\"justification\":\"need expert\"}";

    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, occurredAt);
    PriorAuthorityDataPayload existingDraftPayload =
        new PriorAuthorityDataPayload(
            priorAuthorityId,
            applicationId,
            new PriorAuthorityContent(null, null, null, null, null),
            firstRequest,
            occurredAt);

    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.of(existingDraftPayload));

    UpdatePriorAuthorityDraftCommand command =
        new UpdatePriorAuthorityDraftCommand(
            priorAuthorityId,
            new PriorAuthorityContent(null, "need expert", null, null, null),
            secondRequest,
            1,
            "PriorAuthority.json",
            occurredAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .events(new PriorAuthorityDraftUpdatedEvent(priorAuthorityId, applicationId, occurredAt));

    verify(draftStore)
        .upsert(eq(priorAuthorityId), eq(applicationId), any(), eq(secondRequest), eq(occurredAt));
  }

  @Test
  void givenExistingPriorAuthority_whenCreateDraftAgain_thenThrowsConflictAndPersistsNothing() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, occurredAt);

    CreatePriorAuthorityDraftCommand duplicateCreateCommand =
        new CreatePriorAuthorityDraftCommand(
            priorAuthorityId,
            applicationId,
            new PriorAuthorityContent(EXPERT, null, null, null, null),
            serialisedRequest,
            1,
            "PriorAuthority.json",
            occurredAt.plusSeconds(60));

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(duplicateCreateCommand)
        .then()
        .exception(PriorAuthorityCreationConflictException.class)
        .noEvents();

    verify(draftStore, never()).upsert(any(), any(), any(), any(), any());
  }

  @Test
  void givenStateTypeSet_whenUpdateDraftWithoutType_thenPersistsStateType() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    PriorAuthorityContent existingContent = new PriorAuthorityContent(null, null, null, null, null);
    PriorAuthorityDataPayload existingDraftPayload =
        new PriorAuthorityDataPayload(
            priorAuthorityId, applicationId, existingContent, serialisedRequest, occurredAt);
    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, occurredAt);

    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.of(existingDraftPayload));

    UpdatePriorAuthorityDraftCommand command =
        new UpdatePriorAuthorityDraftCommand(
            priorAuthorityId,
            new PriorAuthorityContent(null, "updated justification", null, null, null),
            "{\"justification\":\"updated justification\"}",
            1,
            "PriorAuthority.json",
            occurredAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .events(new PriorAuthorityDraftUpdatedEvent(priorAuthorityId, applicationId, occurredAt));

    ArgumentCaptor<PriorAuthorityDataPayload> payloadCaptor =
        ArgumentCaptor.forClass(PriorAuthorityDataPayload.class);
    verify(draftStore)
        .upsert(
            eq(priorAuthorityId),
            eq(applicationId),
            payloadCaptor.capture(),
            eq("{\"justification\":\"updated justification\"}"),
            eq(occurredAt));
    assertThat(payloadCaptor.getValue().content().priorAuthorityType()).isEqualTo(EXPERT);
  }

  @Test
  void givenStateTypeMissing_whenUpdateDraftWithoutType_thenThrowsNullPointerException() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    PriorAuthorityDataPayload existingDraftPayload =
        new PriorAuthorityDataPayload(
            priorAuthorityId,
            applicationId,
            new PriorAuthorityContent(null, null, null, null, null),
            "{}",
            occurredAt);
    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(priorAuthorityId, applicationId, null, 1, occurredAt);

    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.of(existingDraftPayload));

    UpdatePriorAuthorityDraftCommand command =
        new UpdatePriorAuthorityDraftCommand(
            priorAuthorityId,
            new PriorAuthorityContent(null, "updated justification", null, null, null),
            "{\"justification\":\"updated justification\"}",
            1,
            "PriorAuthority.json",
            occurredAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .exception(NullPointerException.class)
        .noEvents();
  }

  @Test
  void givenDraftInProgress_whenSubmit_thenAppendsVersion0AndEmitsSubmittedEventAndDeletesDraft() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant submittedAt = Instant.parse("2026-08-02T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    PriorAuthorityContent content = new PriorAuthorityContent(EXPERT, null, null, null, null);
    PriorAuthorityDataPayload draftPayload =
        new PriorAuthorityDataPayload(
            priorAuthorityId, applicationId, content, serialisedRequest, startedAt);

    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, startedAt);

    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.of(draftPayload));
    when(applicationDataStore.latestVersion(applicationId)).thenReturn(7L);

    SubmitPriorAuthorityDraftCommand command =
        new SubmitPriorAuthorityDraftCommand(priorAuthorityId, submittedAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .events(
            new PriorAuthoritySubmittedEvent(
                priorAuthorityId, applicationId, EXPERT.name(), 1, 0L, 7L, submittedAt));

    verify(jsonSchemaValidator).validate(content, "PriorAuthority.json", 1);
    verify(dataStore)
        .append(priorAuthorityId, 0L, applicationId, draftPayload, serialisedRequest, submittedAt);
    verify(draftStore).delete(priorAuthorityId);
  }

  @Test
  void
      givenSchemaInvalidDraft_whenSubmit_thenThrowsValidationExceptionAndNeitherAppendsNorDeletesDraft() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant submittedAt = Instant.parse("2026-08-02T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    PriorAuthorityContent content = new PriorAuthorityContent(EXPERT, null, null, null, null);
    PriorAuthorityDataPayload draftPayload =
        new PriorAuthorityDataPayload(
            priorAuthorityId, applicationId, content, serialisedRequest, startedAt);

    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, startedAt);

    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.of(draftPayload));
    doThrow(new ValidationException(List.of("expertDetails is required")))
        .when(jsonSchemaValidator)
        .validate(content, "PriorAuthority.json", 1);

    SubmitPriorAuthorityDraftCommand command =
        new SubmitPriorAuthorityDraftCommand(priorAuthorityId, submittedAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .exception(ValidationException.class)
        .noEvents();

    verify(dataStore, never()).append(any(), anyLong(), any(), any(), any(), any());
    verify(draftStore, never()).delete(any());
  }

  @Test
  void givenPendingSubmission_whenUpdateDraft_thenThrowsResourceNotFound() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant submittedAt = Instant.parse("2026-08-02T10:00:00Z");

    PriorAuthorityDraftStartedEvent draftStartedEvent =
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, startedAt);
    PriorAuthoritySubmittedEvent submittedEvent =
        new PriorAuthoritySubmittedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, 0L, 0L, submittedAt);

    UpdatePriorAuthorityDraftCommand command =
        new UpdatePriorAuthorityDraftCommand(
            priorAuthorityId,
            new PriorAuthorityContent(null, null, null, null, null),
            "{}",
            1,
            "PriorAuthority.json",
            submittedAt);

    fixture
        .given()
        .events(draftStartedEvent, submittedEvent)
        .when()
        .command(command)
        .then()
        .exception(ResourceNotFoundException.class)
        .noEvents();
  }

  @Test
  void givenNeverSeenPriorAuthorityId_whenUpdateDraft_thenThrowsResourceNotFound() {
    UUID priorAuthorityId = UUID.randomUUID();

    UpdatePriorAuthorityDraftCommand command =
        new UpdatePriorAuthorityDraftCommand(
            priorAuthorityId,
            new PriorAuthorityContent(null, null, null, null, null),
            "{}",
            1,
            "PriorAuthority.json",
            Instant.parse("2026-08-01T10:00:00Z"));

    fixture
        .given()
        .noPriorActivity()
        .when()
        .command(command)
        .then()
        .exception(ResourceNotFoundException.class)
        .noEvents();
  }

  @Test
  void givenNoDraftInProgress_whenSubmit_thenThrowsResourceNotFound() {
    UUID priorAuthorityId = UUID.randomUUID();

    SubmitPriorAuthorityDraftCommand command =
        new SubmitPriorAuthorityDraftCommand(priorAuthorityId, Instant.now());

    fixture
        .given()
        .noPriorActivity()
        .when()
        .command(command)
        .then()
        .exception(ResourceNotFoundException.class)
        .noEvents();
  }

  @Test
  void givenSubmittedPriorAuthority_whenDecisionMade_thenPersistsNextVersionAndEmitsEvent() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant submittedAt = Instant.parse("2026-08-02T10:00:00Z");
    Instant decidedAt = Instant.parse("2026-08-02T11:00:00Z");
    ExpertFeeInformation expertFee =
        ExpertFeeInformation.builder()
            .newFixedRateAmount(BigDecimal.valueOf(250.0))
            .newHourlyRateAmount(BigDecimal.valueOf(125.0))
            .build();
    DisbursementInformation disbursementInformation =
        DisbursementInformation.builder().newAmount(BigDecimal.valueOf(75.5)).build();
    ApportionmentInformation apportionmentInformation =
        ApportionmentInformation.builder().newClientShareAmount(BigDecimal.valueOf(10.25)).build();
    PriorAuthorityDataPayload current =
        new PriorAuthorityDataPayload(
            priorAuthorityId,
            applicationId,
            new PriorAuthorityContent(EXPERT, "Need expert", null, null, null),
            "{}",
            submittedAt);
    when(dataStore.get(priorAuthorityId, 0L)).thenReturn(current);

    MakePriorAuthorityDecisionCommand command =
        new MakePriorAuthorityDecisionCommand(
            priorAuthorityId,
            TestJwtDecoderConfig.CASEWORKER_ID,
            0L,
            "GRANTED",
            "Decision recorded",
            BigDecimal.valueOf(1234.56),
            expertFee,
            disbursementInformation,
            apportionmentInformation,
            decidedAt,
            "{\"decision\":\"GRANTED\"}",
            decidedAt);

    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, startedAt));
    aggregate.on(
        new PriorAuthoritySubmittedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, 0L, 0L, submittedAt));
    aggregate.on(
        new WorkItemAssigned(
            priorAuthorityId,
            WorkItemType.PRIOR_AUTHORITY,
            0L,
            1L,
            TestJwtDecoderConfig.CASEWORKER_ID,
            submittedAt));

    aggregate.handle(command, dataStore, eventAppender);

    verify(eventAppender)
        .append(
            new PriorAuthorityDecisionMadeEvent(
                priorAuthorityId,
                applicationId,
                EXPERT.name(),
                1L,
                "GRANTED",
                "Decision recorded",
                BigDecimal.valueOf(1234.56),
                decidedAt,
                decidedAt));

    ArgumentCaptor<PriorAuthorityDataPayload> payloadCaptor =
        ArgumentCaptor.forClass(PriorAuthorityDataPayload.class);
    verify(dataStore)
        .append(
            eq(priorAuthorityId),
            eq(1L),
            eq(applicationId),
            payloadCaptor.capture(),
            eq("{\"decision\":\"GRANTED\"}"),
            eq(decidedAt));

    PriorAuthorityDataPayload persisted = payloadCaptor.getValue();
    assertThat(persisted.decision()).isEqualTo("GRANTED");
    assertThat(persisted.decisionJustification()).isEqualTo("Decision recorded");
    assertThat(persisted.amountGranted()).isEqualByComparingTo(BigDecimal.valueOf(1234.56));
    assertThat(persisted.dateGranted()).isEqualTo(decidedAt);
    assertThat(persisted.expert()).isEqualTo(expertFee);
    assertThat(persisted.disbursement()).isEqualTo(disbursementInformation);
    assertThat(persisted.apportionment()).isEqualTo(apportionmentInformation);
    assertThat(persisted.decisionSerialisedRequest()).isEqualTo("{\"decision\":\"GRANTED\"}");
  }

  @Test
  void givenAlreadyDecidedPriorAuthority_whenSameDecisionMade_thenThrowsConflictAndDoesNotAppend() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant submittedAt = Instant.parse("2026-08-02T10:00:00Z");
    Instant firstDecisionAt = Instant.parse("2026-08-02T11:00:00Z");

    MakePriorAuthorityDecisionCommand command =
        new MakePriorAuthorityDecisionCommand(
            priorAuthorityId,
            TestJwtDecoderConfig.CASEWORKER_ID,
            1L,
            "GRANTED",
            "Initial",
            BigDecimal.valueOf(100.0),
            null,
            null,
            null,
            firstDecisionAt,
            "{\"decision\":\"GRANTED\"}",
            firstDecisionAt.plusSeconds(1));

    fixture
        .given()
        .events(
            new PriorAuthorityDraftStartedEvent(
                priorAuthorityId, applicationId, EXPERT.name(), 1, startedAt),
            new PriorAuthoritySubmittedEvent(
                priorAuthorityId, applicationId, EXPERT.name(), 1, 0L, 0L, submittedAt),
            new WorkItemAssigned(
                priorAuthorityId,
                WorkItemType.PRIOR_AUTHORITY,
                0L,
                1L,
                TestJwtDecoderConfig.CASEWORKER_ID,
                submittedAt),
            new PriorAuthorityDecisionMadeEvent(
                priorAuthorityId,
                applicationId,
                EXPERT.name(),
                1L,
                "GRANTED",
                "Initial",
                BigDecimal.valueOf(100.0),
                firstDecisionAt,
                firstDecisionAt))
        .when()
        .command(command)
        .then()
        .exception(PriorAuthorityStatusConflictException.class)
        .noEvents();

    verify(dataStore, never()).append(any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void givenAlreadyDecidedPriorAuthority_whenDifferentDecisionMade_thenThrowsConflict() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant submittedAt = Instant.parse("2026-08-02T10:00:00Z");
    Instant firstDecisionAt = Instant.parse("2026-08-02T11:00:00Z");

    MakePriorAuthorityDecisionCommand command =
        new MakePriorAuthorityDecisionCommand(
            priorAuthorityId,
            TestJwtDecoderConfig.CASEWORKER_ID,
            1L,
            "REFUSED",
            "Changed",
            BigDecimal.ZERO,
            null,
            null,
            null,
            firstDecisionAt,
            "{\"decision\":\"REFUSED\"}",
            firstDecisionAt.plusSeconds(1));

    fixture
        .given()
        .events(
            new PriorAuthorityDraftStartedEvent(
                priorAuthorityId, applicationId, EXPERT.name(), 1, startedAt),
            new PriorAuthoritySubmittedEvent(
                priorAuthorityId, applicationId, EXPERT.name(), 1, 0L, 0L, submittedAt),
            new WorkItemAssigned(
                priorAuthorityId,
                WorkItemType.PRIOR_AUTHORITY,
                0L,
                1L,
                TestJwtDecoderConfig.CASEWORKER_ID,
                submittedAt),
            new PriorAuthorityDecisionMadeEvent(
                priorAuthorityId,
                applicationId,
                EXPERT.name(),
                1L,
                "GRANTED",
                "Initial",
                BigDecimal.valueOf(100.0),
                firstDecisionAt,
                firstDecisionAt))
        .when()
        .command(command)
        .then()
        .exception(PriorAuthorityStatusConflictException.class)
        .noEvents();
  }

  @Test
  void givenNeverInitialized_whenMakePriorAuthorityDecision_thenThrowsResourceNotFound() {
    UUID priorAuthorityId = UUID.randomUUID();
    Instant decidedAt = Instant.parse("2026-08-02T11:00:00Z");

    MakePriorAuthorityDecisionCommand command =
        new MakePriorAuthorityDecisionCommand(
            priorAuthorityId,
            TestJwtDecoderConfig.CASEWORKER_ID,
            0L,
            "GRANTED",
            "Decision recorded",
            BigDecimal.valueOf(1234.56),
            null,
            null,
            null,
            decidedAt,
            "{\"decision\":\"GRANTED\"}",
            decidedAt);

    fixture
        .given()
        .noPriorActivity()
        .when()
        .command(command)
        .then()
        .exception(ResourceNotFoundException.class)
        .noEvents();
  }

  @Test
  void
      givenDraftPriorAuthority_whenMakePriorAuthorityDecision_thenThrowsStatusConflictWithoutReadingDataStore() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant decidedAt = Instant.parse("2026-08-02T11:00:00Z");

    MakePriorAuthorityDecisionCommand command =
        new MakePriorAuthorityDecisionCommand(
            priorAuthorityId,
            TestJwtDecoderConfig.CASEWORKER_ID,
            0L,
            "GRANTED",
            "Decision recorded",
            BigDecimal.valueOf(1234.56),
            null,
            null,
            null,
            decidedAt,
            "{\"decision\":\"GRANTED\"}",
            decidedAt);

    fixture
        .given()
        .events(
            new PriorAuthorityDraftStartedEvent(
                priorAuthorityId, applicationId, EXPERT.name(), 1, startedAt),
            new WorkItemAssigned(
                priorAuthorityId,
                WorkItemType.PRIOR_AUTHORITY,
                0L,
                1L,
                TestJwtDecoderConfig.CASEWORKER_ID,
                startedAt))
        .when()
        .command(command)
        .then()
        .exception(PriorAuthorityStatusConflictException.class)
        .noEvents();

    verify(dataStore, never()).get(any(), anyLong());
    verify(dataStore, never()).append(any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void givenDraftWithoutExistingDocuments_whenUpload_thenPersistsSingleUploadedDocument() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-08T12:00:00Z");
    MockMultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", "content".getBytes());
    UUID documentId = UUID.randomUUID();
    String serialisedRequest =
        "{\"documentId\":\"%s\",\"originalFilename\":\"evidence.pdf\"}".formatted(documentId);
    PriorAuthorityDocumentUploadCommand command =
        new PriorAuthorityDocumentUploadCommand(
            priorAuthorityId,
            documentId,
            "CIVIL_APPLY",
            "sum",
            serialisedRequest,
            occurredAt,
            file.getOriginalFilename(),
            file.getSize(),
            "PDF",
            "application/pdf");

    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, occurredAt));
    when(draftStore.find(priorAuthorityId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    priorAuthorityId,
                    applicationId,
                    new PriorAuthorityContent(EXPERT, "why", null, null, null),
                    "{}",
                    occurredAt)));

    UUID returnedDocumentId =
        aggregate.handle(command, draftStore, uploadedDocumentStore, eventAppender);

    assertThat(returnedDocumentId).isEqualTo(documentId);
    ArgumentCaptor<PriorAuthorityDocument> documentCaptor =
        ArgumentCaptor.forClass(PriorAuthorityDocument.class);
    verify(uploadedDocumentStore).save(eq(priorAuthorityId), documentCaptor.capture());
    assertThat(documentCaptor.getValue().documentType()).isNull();
    assertThat(documentCaptor.getValue().checksum()).isEqualTo("sum");
    verify(draftStore, never()).upsert(any(), any(), any(), any(), any());
    verify(eventAppender).append(any(PriorAuthorityDocumentUploadedEvent.class));
  }

  @Test
  void givenDraftWithExistingDocument_whenUpload_thenAppendsToDocumentList() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-08T12:00:00Z");
    MockMultipartFile file =
        new MockMultipartFile("file", "second.pdf", "application/pdf", "content".getBytes());
    UUID documentId = UUID.randomUUID();

    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, occurredAt));
    when(draftStore.find(priorAuthorityId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    priorAuthorityId,
                    applicationId,
                    new PriorAuthorityContent(
                        EXPERT,
                        "why",
                        null,
                        null,
                        null,
                        List.of(
                            new PriorAuthorityDocument(
                                UUID.randomUUID(),
                                "gateway_evidence",
                                "first.pdf",
                                "PDF",
                                "application/pdf",
                                1L,
                                occurredAt,
                                "CIVIL_APPLY",
                                "first-checksum"))),
                    "{}",
                    occurredAt)));

    final PriorAuthorityDocumentUploadCommand priorAuthorityDocumentUploadCommand =
        new PriorAuthorityDocumentUploadCommand(
            priorAuthorityId,
            documentId,
            "CIVIL_APPLY",
            "sum",
            "{}",
            occurredAt,
            file.getOriginalFilename(),
            file.getSize(),
            "PDF",
            "application/pdf");

    aggregate.handle(
        priorAuthorityDocumentUploadCommand, draftStore, uploadedDocumentStore, eventAppender);

    verify(uploadedDocumentStore).save(eq(priorAuthorityId), any(PriorAuthorityDocument.class));
    verify(draftStore, never()).upsert(any(), any(), any(), any(), any());
  }

  @Test
  void givenMissingDraft_whenUpload_thenThrowsNotFound() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-08T12:00:00Z");
    MockMultipartFile file =
        new MockMultipartFile("file", "missing.pdf", "application/pdf", "content".getBytes());
    UUID documentId = UUID.randomUUID();

    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, occurredAt));
    when(draftStore.find(priorAuthorityId)).thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(
            () -> {
              PriorAuthorityDocumentUploadCommand priorAuthorityDocumentUploadCommand =
                  new PriorAuthorityDocumentUploadCommand(
                      priorAuthorityId,
                      documentId,
                      "CIVIL_APPLY",
                      "sum",
                      "{}",
                      occurredAt,
                      file.getOriginalFilename(),
                      file.getSize(),
                      "PDF",
                      "application/pdf");
              aggregate.handle(
                  priorAuthorityDocumentUploadCommand,
                  draftStore,
                  uploadedDocumentStore,
                  eventAppender);
            });
  }

  @Test
  void givenDraftWithDocument_whenUpdateDocumentType_thenPersistsUpdatedDocumentAndEmitsEvent() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID documentTwoId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-08T12:00:00Z");
    String serialisedRequest = "{\"documentType\":\"GATEWAY_EVIDENCE\"}";
    PriorAuthorityDocumentTypeUpdateCommand command =
        new PriorAuthorityDocumentTypeUpdateCommand(
            priorAuthorityId, documentId, "GATEWAY_EVIDENCE", serialisedRequest, occurredAt);
    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, occurredAt));
    when(draftStore.find(priorAuthorityId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    priorAuthorityId,
                    applicationId,
                    new PriorAuthorityContent(
                        EXPERT,
                        "why",
                        null,
                        null,
                        null,
                        List.of(
                            new PriorAuthorityDocument(
                                documentId,
                                null,
                                "evidence.pdf",
                                "PDF",
                                "application/pdf",
                                1L,
                                occurredAt,
                                "CIVIL_APPLY",
                                "checksum"),
                            new PriorAuthorityDocument(
                                documentTwoId,
                                null,
                                "other_evidence.pdf",
                                "PDF",
                                "application/pdf",
                                1L,
                                occurredAt,
                                "CIVIL_APPLY",
                                "checksum"))),
                    "{}",
                    occurredAt)));

    assertThat(aggregate.handle(command, draftStore, uploadedDocumentStore, eventAppender))
        .isEqualTo(documentId);

    verify(uploadedDocumentStore)
        .updateDocumentType(priorAuthorityId, documentId, "GATEWAY_EVIDENCE");
    verify(draftStore, never()).upsert(any(), any(), any(), any(), any());
    verify(eventAppender)
        .append(
            new PriorAuthorityDocumentTypeUpdatedEvent(
                priorAuthorityId, documentId, "GATEWAY_EVIDENCE", occurredAt));
  }

  @Test
  void givenDraftWithMultipleTypedDocuments_whenChangeDocumentType_thenOnlyUpdatesTargetDocument() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID documentTwoId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-08T12:00:00Z");
    PriorAuthorityDocumentTypeUpdateCommand command =
        new PriorAuthorityDocumentTypeUpdateCommand(
            priorAuthorityId, documentId, "GATEWAY_EVIDENCE", "{}", occurredAt);
    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, occurredAt));
    when(draftStore.find(priorAuthorityId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    priorAuthorityId,
                    applicationId,
                    new PriorAuthorityContent(
                        EXPERT,
                        "why",
                        null,
                        null,
                        null,
                        List.of(
                            new PriorAuthorityDocument(
                                documentId,
                                "INVOICE",
                                "invoice.pdf",
                                "PDF",
                                "application/pdf",
                                1L,
                                occurredAt,
                                "CIVIL_APPLY",
                                "checksum"),
                            new PriorAuthorityDocument(
                                documentTwoId,
                                "EXPERT_REPORT",
                                "report.pdf",
                                "PDF",
                                "application/pdf",
                                1L,
                                occurredAt,
                                "CIVIL_APPLY",
                                "checksum"))),
                    "{}",
                    occurredAt)));

    aggregate.handle(command, draftStore, uploadedDocumentStore, eventAppender);

    verify(uploadedDocumentStore)
        .updateDocumentType(priorAuthorityId, documentId, "GATEWAY_EVIDENCE");
  }

  @Test
  void givenInvalidDocumentType_whenUpdateDocumentType_thenRejectsBeforeReadingDraft() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    PriorAuthorityDocumentTypeUpdateCommand command =
        new PriorAuthorityDocumentTypeUpdateCommand(
            UUID.randomUUID(), UUID.randomUUID(), "INVALID", "{}", Instant.now());

    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> aggregate.handle(command, draftStore, uploadedDocumentStore, eventAppender));

    verify(draftStore, never()).find(any());
    verify(eventAppender, never()).append(any(PriorAuthorityDocumentTypeUpdatedEvent.class));
  }

  @Test
  void givenChecksumMissing_whenUpload_thenEmitsEventWithNullChecksum() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-08T12:00:00Z");
    MockMultipartFile file =
        new MockMultipartFile("file", "nullsum.pdf", "application/pdf", "content".getBytes());
    UUID documentId = UUID.randomUUID();

    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, occurredAt));
    when(draftStore.find(priorAuthorityId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    priorAuthorityId,
                    applicationId,
                    new PriorAuthorityContent(EXPERT, "why", null, null, null),
                    "{}",
                    occurredAt)));

    final PriorAuthorityDocumentUploadCommand priorAuthorityDocumentUploadCommand =
        new PriorAuthorityDocumentUploadCommand(
            priorAuthorityId,
            documentId,
            "CIVIL_APPLY",
            null,
            "{}",
            occurredAt,
            file.getOriginalFilename(),
            file.getSize(),
            "PDF",
            "application/pdf");

    aggregate.handle(
        priorAuthorityDocumentUploadCommand, draftStore, uploadedDocumentStore, eventAppender);

    ArgumentCaptor<PriorAuthorityDocumentUploadedEvent> eventCaptor =
        ArgumentCaptor.forClass(PriorAuthorityDocumentUploadedEvent.class);
    verify(eventAppender).append(eventCaptor.capture());
    assertThat(eventCaptor.getValue().checksum()).isNull();
  }

  @Test
  void givenDraftWithDocument_whenDelete_thenRemovesItAndEmitsDeletedEvent() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID remainingDocumentId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-08T12:00:00Z");
    PriorAuthorityDocumentDeleteCommand command =
        new PriorAuthorityDocumentDeleteCommand(priorAuthorityId, documentId, "{}", occurredAt);

    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, occurredAt));
    when(draftStore.find(priorAuthorityId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    priorAuthorityId,
                    applicationId,
                    new PriorAuthorityContent(
                        EXPERT,
                        "why",
                        null,
                        null,
                        null,
                        List.of(
                            new PriorAuthorityDocument(
                                documentId,
                                null,
                                "delete.pdf",
                                "PDF",
                                "application/pdf",
                                1L,
                                occurredAt,
                                "CIVIL_APPLY",
                                "delete-checksum"),
                            new PriorAuthorityDocument(
                                remainingDocumentId,
                                null,
                                "keep.pdf",
                                "PDF",
                                "application/pdf",
                                1L,
                                occurredAt,
                                "CIVIL_APPLY",
                                "keep-checksum"))),
                    "{}",
                    occurredAt)));

    assertThat(aggregate.handle(command, draftStore, uploadedDocumentStore, eventAppender))
        .isEqualTo(documentId);

    verify(uploadedDocumentStore).delete(priorAuthorityId, documentId);
    verify(draftStore, never()).upsert(any(), any(), any(), any(), any());
    verify(eventAppender)
        .append(
            new PriorAuthorityDocumentDeletedEvent(
                priorAuthorityId, documentId, occurredAt, applicationId));
  }

  @Test
  void givenDraftWithoutDocuments_whenDelete_thenThrowsNotFoundWithoutPersistingChanges() {
    PriorAuthorityAggregate aggregate = new PriorAuthorityAggregate();
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-08T12:00:00Z");
    PriorAuthorityDocumentDeleteCommand command =
        new PriorAuthorityDocumentDeleteCommand(priorAuthorityId, documentId, "{}", occurredAt);

    aggregate.on(
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, occurredAt));
    when(draftStore.find(priorAuthorityId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    priorAuthorityId,
                    applicationId,
                    new PriorAuthorityContent(EXPERT, "why", null, null, null),
                    "{}",
                    occurredAt)));

    doThrow(
            new ResourceNotFoundException(
                "Document %s not found for Prior Authority %s"
                    .formatted(documentId, priorAuthorityId)))
        .when(uploadedDocumentStore)
        .delete(priorAuthorityId, documentId);

    org.assertj.core.api.Assertions.assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(
            () -> aggregate.handle(command, draftStore, uploadedDocumentStore, eventAppender));

    verify(draftStore, never()).upsert(any(), any(), any(), any(), any());
    verify(eventAppender, never()).append(any(PriorAuthorityDocumentDeletedEvent.class));
  }
}
