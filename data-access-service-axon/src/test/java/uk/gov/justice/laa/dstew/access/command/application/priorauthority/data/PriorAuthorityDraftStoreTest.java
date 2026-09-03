package uk.gov.justice.laa.dstew.access.command.application.priorauthority.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityDocument;
import uk.gov.justice.laa.dstew.access.util.PayloadFingerprint;

class PriorAuthorityDraftStoreTest {

  private PriorAuthorityDraftRepository repository;
  private PriorAuthorityDraftStore store;

  @BeforeEach
  void setUp() {
    repository = mock(PriorAuthorityDraftRepository.class);
    store = new PriorAuthorityDraftStore(repository);
  }

  @Test
  void
      givenNoExistingDraft_whenUpsert_thenInsertsRowWithOccurredAtAsCreatedAtAndReturnsFingerprint() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityContent content =
        new PriorAuthorityContent("EXPERT", null, null, null, null, List.of());
    Instant occurredAt = Instant.parse("2026-08-01T10:00:00Z");
    PriorAuthorityDataPayload payload =
        new PriorAuthorityDataPayload(
            submissionId, applicationId, content, "request-json", occurredAt);

    when(repository.findById(submissionId)).thenReturn(Optional.empty());

    String fingerprint =
        store.upsert(submissionId, applicationId, payload, "request-json", occurredAt);

    assertThat(fingerprint).isEqualTo(PayloadFingerprint.compute("request-json")).hasSize(64);
    ArgumentCaptor<PriorAuthorityDraft> captor = ArgumentCaptor.forClass(PriorAuthorityDraft.class);
    verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getSubmissionId()).isEqualTo(submissionId);
    assertThat(captor.getValue().getApplicationId()).isEqualTo(applicationId);
    assertThat(captor.getValue().getPayload()).isEqualTo(payload);
    assertThat(captor.getValue().getPayloadHash()).isEqualTo(fingerprint);
    assertThat(captor.getValue().getCreatedAt()).isEqualTo(occurredAt);
    assertThat(captor.getValue().getUpdatedAt()).isEqualTo(occurredAt);
  }

  @Test
  void givenExistingDraft_whenUpsert_thenPreservesOriginalCreatedAtAndUpdatesUpdatedAt() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant originalCreatedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant secondOccurredAt = Instant.parse("2026-08-02T11:00:00Z");
    PriorAuthorityDraft existing =
        PriorAuthorityDraft.builder()
            .submissionId(submissionId)
            .applicationId(applicationId)
            .createdAt(originalCreatedAt)
            .updatedAt(originalCreatedAt)
            .build();
    PriorAuthorityDataPayload payload =
        new PriorAuthorityDataPayload(
            submissionId, applicationId, null, "second-request", secondOccurredAt);

    when(repository.findById(submissionId)).thenReturn(Optional.of(existing));

    store.upsert(submissionId, applicationId, payload, "second-request", secondOccurredAt);

    ArgumentCaptor<PriorAuthorityDraft> captor = ArgumentCaptor.forClass(PriorAuthorityDraft.class);
    verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getCreatedAt()).isEqualTo(originalCreatedAt);
    assertThat(captor.getValue().getUpdatedAt()).isEqualTo(secondOccurredAt);
  }

  @Test
  void givenStoredDraft_whenGet_thenReturnsPayload() {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityDataPayload expectedPayload =
        new PriorAuthorityDataPayload(submissionId, UUID.randomUUID(), null, "req", Instant.now());
    when(repository.findById(submissionId))
        .thenReturn(Optional.of(PriorAuthorityDraft.builder().payload(expectedPayload).build()));

    PriorAuthorityDataPayload result = store.get(submissionId);

    assertThat(result).isEqualTo(expectedPayload);
  }

  @Test
  void givenNoDraft_whenGet_thenThrowsIllegalStateException() {
    UUID submissionId = UUID.randomUUID();
    when(repository.findById(submissionId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> store.get(submissionId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No draft found for submission: " + submissionId);
  }

  @Test
  void whenDelete_thenDelegatesToRepositoryDeleteById() {
    UUID submissionId = UUID.randomUUID();

    store.delete(submissionId);

    verify(repository).deleteById(eq(submissionId));
  }

  @Test
  void givenExistingDraft_whenAppendDocument_thenAddsDocumentAndPreservesOtherFields() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant attachedAt = Instant.parse("2026-08-02T11:00:00Z");
    PriorAuthorityContent content =
        new PriorAuthorityContent("EXPERT", null, null, null, null, List.of());
    PriorAuthorityDataPayload payload =
        new PriorAuthorityDataPayload(
            submissionId, applicationId, content, "request-json", createdAt);
    PriorAuthorityDraft existing =
        PriorAuthorityDraft.builder()
            .submissionId(submissionId)
            .applicationId(applicationId)
            .payload(payload)
            .payloadHash("original-hash")
            .createdAt(createdAt)
            .updatedAt(createdAt)
            .build();
    PriorAuthorityDocument document = new PriorAuthorityDocument(documentId, "evidence.pdf");

    when(repository.findById(submissionId)).thenReturn(Optional.of(existing));

    store.appendDocument(submissionId, document, attachedAt);

    ArgumentCaptor<PriorAuthorityDraft> captor = ArgumentCaptor.forClass(PriorAuthorityDraft.class);
    verify(repository).saveAndFlush(captor.capture());
    PriorAuthorityDraft saved = captor.getValue();
    assertThat(saved.getSubmissionId()).isEqualTo(submissionId);
    assertThat(saved.getApplicationId()).isEqualTo(applicationId);
    assertThat(saved.getPayloadHash()).isEqualTo("original-hash");
    assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
    assertThat(saved.getUpdatedAt()).isEqualTo(attachedAt);
    PriorAuthorityContent savedContent = saved.getPayload().content();
    assertThat(savedContent.priorAuthorityType()).isEqualTo("EXPERT");
    assertThat(savedContent.documents()).containsExactly(document);
  }

  @Test
  void givenDraftWithExistingDocuments_whenAppendDocument_thenAppendsWithoutRemovingExisting() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.now();
    PriorAuthorityDocument firstDocument =
        new PriorAuthorityDocument(UUID.randomUUID(), "first.pdf");
    PriorAuthorityDocument secondDocument =
        new PriorAuthorityDocument(UUID.randomUUID(), "second.pdf");
    PriorAuthorityContent content =
        new PriorAuthorityContent(
            "EXPERT", null, null, null, null, java.util.List.of(firstDocument));
    PriorAuthorityDataPayload payload =
        new PriorAuthorityDataPayload(submissionId, applicationId, content, "{}", occurredAt);
    PriorAuthorityDraft existing =
        PriorAuthorityDraft.builder()
            .submissionId(submissionId)
            .applicationId(applicationId)
            .payload(payload)
            .createdAt(occurredAt)
            .updatedAt(occurredAt)
            .build();

    when(repository.findById(submissionId)).thenReturn(Optional.of(existing));

    store.appendDocument(submissionId, secondDocument, occurredAt);

    ArgumentCaptor<PriorAuthorityDraft> captor = ArgumentCaptor.forClass(PriorAuthorityDraft.class);
    verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getPayload().content().documents())
        .containsExactly(firstDocument, secondDocument);
  }

  @Test
  void givenNoDraft_whenAppendDocument_thenThrowsIllegalStateException() {
    UUID submissionId = UUID.randomUUID();
    when(repository.findById(submissionId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                store.appendDocument(
                    submissionId,
                    new PriorAuthorityDocument(UUID.randomUUID(), "file.pdf"),
                    Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No draft found for submission: " + submissionId);
  }
}
