package uk.gov.justice.laa.dstew.access.command.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataStore;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDraftStore;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityStatus;
import uk.gov.justice.laa.dstew.access.exception.PriorAuthorityCreationConflictException;
import uk.gov.justice.laa.dstew.access.exception.PriorAuthorityNotInProgressException;
import uk.gov.justice.laa.dstew.access.util.PayloadFingerprint;
import uk.gov.justice.laa.dstew.access.validation.JsonSchemaValidator;
import uk.gov.justice.laa.dstew.access.validation.ValidationException;

/** Integration tests for {@link PriorAuthorityAggregate} using the Axon test fixture. */
class PriorAuthorityAggregateTest {

  private AxonTestFixture fixture;
  private PriorAuthorityDataStore dataStore;
  private PriorAuthorityDraftStore draftStore;
  private JsonSchemaValidator jsonSchemaValidator;

  @BeforeEach
  void setUp() {
    dataStore = mock(PriorAuthorityDataStore.class);
    draftStore = mock(PriorAuthorityDraftStore.class);
    jsonSchemaValidator = mock(JsonSchemaValidator.class);
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
                                JsonSchemaValidator.class, configuration -> jsonSchemaValidator)));
  }

  @Test
  void givenNewAggregate_whenCreate_thenPersistsVersion0AndEmitsCreatedEvent() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    PriorAuthorityContent content =
        new PriorAuthorityContent("EXPERT", null, null, null, null, List.of());
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);

    when(dataStore.append(
            eq(submissionId),
            eq(0L),
            eq(applicationId),
            any(),
            eq(serialisedRequest),
            eq(occurredAt)))
        .thenReturn(fingerprint);

    CreatePriorAuthorityCommand command =
        new CreatePriorAuthorityCommand(
            submissionId, applicationId, content, serialisedRequest, 1, "pa-schema", occurredAt);

    fixture
        .given()
        .noPriorActivity()
        .when()
        .command(command)
        .then()
        .events(
            new PriorAuthorityCreatedEvent(
                submissionId,
                applicationId,
                0L,
                fingerprint,
                PriorAuthorityStatus.PENDING.name(),
                1,
                occurredAt));

    // Verify version-0 payload was persisted with correct structure
    ArgumentCaptor<PriorAuthorityDataPayload> payloadCaptor =
        ArgumentCaptor.forClass(PriorAuthorityDataPayload.class);
    verify(dataStore)
        .append(
            eq(submissionId),
            eq(0L),
            eq(applicationId),
            payloadCaptor.capture(),
            eq(serialisedRequest),
            eq(occurredAt));
    PriorAuthorityDataPayload persisted = payloadCaptor.getValue();
    assertThat(persisted.submissionId()).isEqualTo(submissionId);
    assertThat(persisted.applicationId()).isEqualTo(applicationId);
    assertThat(persisted.content()).isEqualTo(content);
    assertThat(persisted.serialisedRequest()).isEqualTo(serialisedRequest);
    assertThat(persisted.submittedAt()).isEqualTo(occurredAt);
  }

  @Test
  void givenExistingAggregate_whenIdenticalSerialisedRequest_thenEmitsNoEventAndNeverCallsAppend() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);

    PriorAuthorityCreatedEvent existingEvent =
        new PriorAuthorityCreatedEvent(
            submissionId,
            applicationId,
            0L,
            fingerprint,
            PriorAuthorityStatus.PENDING.name(),
            1,
            occurredAt);

    CreatePriorAuthorityCommand command =
        new CreatePriorAuthorityCommand(
            submissionId,
            applicationId,
            new PriorAuthorityContent("EXPERT", null, null, null, null, List.of()),
            serialisedRequest,
            1,
            "pa-schema",
            occurredAt);

    fixture.given().events(existingEvent).when().command(command).then().noEvents();

    verify(dataStore, never()).append(any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void
      givenExistingAggregate_whenDifferentSerialisedRequest_thenThrowsConflictAndNeverCallsAppend() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    String originalRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String fingerprint = PayloadFingerprint.compute(originalRequest);

    PriorAuthorityCreatedEvent existingEvent =
        new PriorAuthorityCreatedEvent(
            submissionId,
            applicationId,
            0L,
            fingerprint,
            PriorAuthorityStatus.PENDING.name(),
            1,
            occurredAt);

    CreatePriorAuthorityCommand command =
        new CreatePriorAuthorityCommand(
            submissionId,
            applicationId,
            new PriorAuthorityContent("COUNSEL", null, null, null, null, List.of()),
            "{\"priorAuthorityType\":\"COUNSEL\"}",
            1,
            "pa-schema",
            occurredAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .exception(PriorAuthorityCreationConflictException.class)
        .noEvents();

    verify(dataStore, never()).append(any(), anyLong(), any(), any(), any(), any());
  }

  @AfterEach
  void tearDown() {
    fixture.stop();
  }

  @Test
  void givenNewAggregate_whenSaveDraft_thenWritesDraftAndEmitsDraftStartedEvent() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    PriorAuthorityContent content =
        new PriorAuthorityContent(null, null, null, null, null, List.of());
    String serialisedRequest = "{}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);

    when(draftStore.upsert(
            eq(submissionId), eq(applicationId), any(), eq(serialisedRequest), eq(occurredAt)))
        .thenReturn(fingerprint);

    SavePriorAuthorityDraftCommand command =
        new SavePriorAuthorityDraftCommand(
            submissionId,
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
                submissionId, applicationId, fingerprint, 1, occurredAt));

    ArgumentCaptor<PriorAuthorityDataPayload> payloadCaptor =
        ArgumentCaptor.forClass(PriorAuthorityDataPayload.class);
    verify(draftStore)
        .upsert(
            eq(submissionId),
            eq(applicationId),
            payloadCaptor.capture(),
            eq(serialisedRequest),
            eq(occurredAt));
    PriorAuthorityDataPayload persisted = payloadCaptor.getValue();
    assertThat(persisted.submissionId()).isEqualTo(submissionId);
    assertThat(persisted.applicationId()).isEqualTo(applicationId);
  }

  @Test
  void givenExistingDraftAggregate_whenSaveDraft_thenPersistsDraftAndEmitsNoEvent() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    String firstRequest = "{}";
    String firstFingerprint = PayloadFingerprint.compute(firstRequest);
    String secondRequest = "{\"justification\":\"need expert\"}";
    String secondFingerprint = PayloadFingerprint.compute(secondRequest);

    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(
            submissionId, applicationId, firstFingerprint, 1, occurredAt);
    PriorAuthorityDataPayload existingDraftPayload =
        new PriorAuthorityDataPayload(
            submissionId,
            applicationId,
            new PriorAuthorityContent(null, null, null, null, null, List.of()),
            firstRequest,
            occurredAt);

    when(draftStore.find(submissionId)).thenReturn(Optional.of(existingDraftPayload));
    when(draftStore.upsert(
            eq(submissionId), eq(applicationId), any(), eq(secondRequest), eq(occurredAt)))
        .thenReturn(secondFingerprint);

    SavePriorAuthorityDraftCommand command =
        new SavePriorAuthorityDraftCommand(
            submissionId,
            null,
            new PriorAuthorityContent(null, "need expert", null, null, null, List.of()),
            secondRequest,
            1,
            "PriorAuthority.json",
            occurredAt);

    fixture.given().events(existingEvent).when().command(command).then().noEvents();

    verify(draftStore)
        .upsert(eq(submissionId), eq(applicationId), any(), eq(secondRequest), eq(occurredAt));
  }

  @Test
  void givenDraftInProgress_whenSubmit_thenAppendsVersion0AndEmitsSubmittedEventAndDeletesDraft() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant submittedAt = Instant.parse("2026-08-02T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);
    PriorAuthorityContent content =
        new PriorAuthorityContent("EXPERT", null, null, null, null, List.of());
    PriorAuthorityDataPayload draftPayload =
        new PriorAuthorityDataPayload(
            submissionId, applicationId, content, serialisedRequest, startedAt);

    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(submissionId, applicationId, fingerprint, 1, startedAt);

    when(draftStore.find(submissionId)).thenReturn(Optional.of(draftPayload));

    SubmitPriorAuthorityDraftCommand command =
        new SubmitPriorAuthorityDraftCommand(submissionId, submittedAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .events(
            new PriorAuthoritySubmittedEvent(
                submissionId, applicationId, 0L, PriorAuthorityStatus.PENDING.name(), submittedAt));

    verify(jsonSchemaValidator).validate(content, "PriorAuthority.json", 1);
    verify(dataStore)
        .append(submissionId, 0L, applicationId, draftPayload, serialisedRequest, submittedAt);
    verify(draftStore).delete(submissionId);
  }

  @Test
  void
      givenSchemaInvalidDraft_whenSubmit_thenThrowsValidationExceptionAndNeitherAppendsNorDeletesDraft() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant submittedAt = Instant.parse("2026-08-02T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);
    PriorAuthorityContent content =
        new PriorAuthorityContent("EXPERT", null, null, null, null, List.of());
    PriorAuthorityDataPayload draftPayload =
        new PriorAuthorityDataPayload(
            submissionId, applicationId, content, serialisedRequest, startedAt);

    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(submissionId, applicationId, fingerprint, 1, startedAt);

    when(draftStore.find(submissionId)).thenReturn(Optional.of(draftPayload));
    doThrow(new ValidationException(List.of("expertDetails is required")))
        .when(jsonSchemaValidator)
        .validate(content, "PriorAuthority.json", 1);

    SubmitPriorAuthorityDraftCommand command =
        new SubmitPriorAuthorityDraftCommand(submissionId, submittedAt);

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
  void givenPendingSubmission_whenSaveDraft_thenThrowsNotInProgress() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);

    PriorAuthorityCreatedEvent existingEvent =
        new PriorAuthorityCreatedEvent(
            submissionId,
            applicationId,
            0L,
            fingerprint,
            PriorAuthorityStatus.PENDING.name(),
            1,
            occurredAt);

    SavePriorAuthorityDraftCommand command =
        new SavePriorAuthorityDraftCommand(
            submissionId,
            null,
            new PriorAuthorityContent(null, null, null, null, null, List.of()),
            "{}",
            1,
            "PriorAuthority.json",
            occurredAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .exception(PriorAuthorityNotInProgressException.class)
        .noEvents();
  }

  @Test
  void givenNoDraftInProgress_whenSubmit_thenThrowsNotInProgress() {
    UUID submissionId = UUID.randomUUID();

    SubmitPriorAuthorityDraftCommand command =
        new SubmitPriorAuthorityDraftCommand(submissionId, Instant.now());

    fixture
        .given()
        .noPriorActivity()
        .when()
        .command(command)
        .then()
        .exception(PriorAuthorityNotInProgressException.class)
        .noEvents();
  }

  @Test
  void givenDraftInProgress_whenAttachDocument_thenAppendsDocumentAndEmitsAttachedEvent() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    Instant startedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant attachedAt = Instant.parse("2026-08-02T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);
    PriorAuthorityDataPayload draftPayload =
        new PriorAuthorityDataPayload(
            submissionId,
            applicationId,
            new PriorAuthorityContent("EXPERT", null, null, null, null, List.of()),
            serialisedRequest,
            startedAt);

    PriorAuthorityDraftStartedEvent existingEvent =
        new PriorAuthorityDraftStartedEvent(submissionId, applicationId, fingerprint, 1, startedAt);

    when(draftStore.find(submissionId)).thenReturn(Optional.of(draftPayload));

    AttachPriorAuthorityDocumentCommand command =
        new AttachPriorAuthorityDocumentCommand(
            submissionId,
            documentId,
            "evidence.pdf",
            1024L,
            "pdf",
            "application/pdf",
            "checksum-123",
            attachedAt);

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .events(
            new PriorAuthorityDocumentAttachedEvent(
                submissionId,
                documentId,
                1024L,
                "pdf",
                "application/pdf",
                "checksum-123",
                attachedAt));

    verify(draftStore)
        .appendDocument(
            eq(submissionId),
            eq(
                new uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityDocument(
                    documentId, "evidence.pdf")),
            eq(attachedAt));
  }

  @Test
  void givenNoAggregate_whenAttachDocument_thenThrowsNotInProgressAndNeverCallsAppendDocument() {
    UUID submissionId = UUID.randomUUID();

    AttachPriorAuthorityDocumentCommand command =
        new AttachPriorAuthorityDocumentCommand(
            submissionId,
            UUID.randomUUID(),
            "evidence.pdf",
            1024L,
            "pdf",
            "application/pdf",
            "checksum-123",
            Instant.now());

    fixture
        .given()
        .noPriorActivity()
        .when()
        .command(command)
        .then()
        .exception(PriorAuthorityNotInProgressException.class)
        .noEvents();

    verify(draftStore, never()).appendDocument(any(), any(), any());
  }

  @Test
  void
      givenAggregateExistsButNoDraftInProgress_whenAttachDocument_thenThrowsNotInProgressAndNeverCallsAppendDocument() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    String serialisedRequest = "{\"priorAuthorityType\":\"EXPERT\"}";
    String fingerprint = PayloadFingerprint.compute(serialisedRequest);

    PriorAuthorityCreatedEvent existingEvent =
        new PriorAuthorityCreatedEvent(
            submissionId,
            applicationId,
            0L,
            fingerprint,
            PriorAuthorityStatus.PENDING.name(),
            1,
            occurredAt);

    AttachPriorAuthorityDocumentCommand command =
        new AttachPriorAuthorityDocumentCommand(
            submissionId,
            UUID.randomUUID(),
            "evidence.pdf",
            1024L,
            "pdf",
            "application/pdf",
            "checksum-123",
            Instant.now());

    fixture
        .given()
        .events(existingEvent)
        .when()
        .command(command)
        .then()
        .exception(PriorAuthorityNotInProgressException.class)
        .noEvents();

    verify(draftStore, never()).appendDocument(any(), any(), any());
  }
}
