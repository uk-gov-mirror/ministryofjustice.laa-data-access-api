package uk.gov.justice.laa.dstew.access.command.application.priorauthority.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityDocument;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class UploadedDocumentStoreTest {

  @Mock private UploadedDocumentRepository repository;

  @InjectMocks private UploadedDocumentStore store;

  @Test
  void givenDocument_whenSaved_thenPersistsItsMetadata() {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityDocument document = document(UUID.randomUUID(), "EVIDENCE");

    store.save(submissionId, document);

    ArgumentCaptor<UploadedDocument> captor = ArgumentCaptor.forClass(UploadedDocument.class);
    verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getDocumentId()).isEqualTo(document.documentId());
    assertThat(captor.getValue().getSubmissionId()).isEqualTo(submissionId);
    assertThat(captor.getValue().getOriginalFilename()).isEqualTo(document.fileName());
    assertThat(captor.getValue().getDocumentType()).isEqualTo(document.documentType());
    assertThat(captor.getValue().getMetadata()).isEqualTo(UploadedDocumentMetadata.from(document));
  }

  @Test
  void givenOwnedDocument_whenTypeUpdated_thenPersistsReplacement() {
    UUID submissionId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UploadedDocument storedDocument = storedDocument(submissionId, documentId, "EVIDENCE");
    when(repository.findById(documentId)).thenReturn(Optional.of(storedDocument));

    store.updateDocumentType(submissionId, documentId, "INVOICE");

    ArgumentCaptor<UploadedDocument> captor = ArgumentCaptor.forClass(UploadedDocument.class);
    verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getDocumentType()).isEqualTo("INVOICE");
  }

  @Test
  void givenMissingDocument_whenTypeUpdated_thenThrowsNotFound() {
    UUID submissionId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    when(repository.findById(documentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> store.updateDocumentType(submissionId, documentId, "INVOICE"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void givenDocumentOwnedByAnotherSubmission_whenDeleted_thenThrowsNotFound() {
    UUID documentId = UUID.randomUUID();
    when(repository.findById(documentId))
        .thenReturn(Optional.of(storedDocument(UUID.randomUUID(), documentId, "EVIDENCE")));

    assertThatThrownBy(() -> store.delete(UUID.randomUUID(), documentId))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void givenOwnedDocument_whenDeleted_thenDeletesStoredMetadata() {
    UUID submissionId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UploadedDocument storedDocument = storedDocument(submissionId, documentId, "EVIDENCE");
    when(repository.findById(documentId)).thenReturn(Optional.of(storedDocument));

    store.delete(submissionId, documentId);

    verify(repository).delete(storedDocument);
  }

  @Test
  void givenStoredDocuments_whenFound_thenReturnsRequestedOrderAndSkipsMissingDocuments() {
    UUID firstDocumentId = UUID.randomUUID();
    UUID missingDocumentId = UUID.randomUUID();
    UUID secondDocumentId = UUID.randomUUID();
    UploadedDocument first = storedDocument(UUID.randomUUID(), firstDocumentId, "EVIDENCE");
    UploadedDocument second = storedDocument(UUID.randomUUID(), secondDocumentId, "INVOICE");
    when(repository.findAllById(List.of(firstDocumentId, missingDocumentId, secondDocumentId)))
        .thenReturn(List.of(second, first));

    List<PriorAuthorityDocument> documents =
        store.findAllInOrder(List.of(firstDocumentId, missingDocumentId, secondDocumentId));

    assertThat(documents)
        .extracting(PriorAuthorityDocument::documentId)
        .containsExactly(firstDocumentId, secondDocumentId);
  }

  private PriorAuthorityDocument document(UUID documentId, String documentType) {
    return new PriorAuthorityDocument(
        documentId,
        documentType,
        "evidence.pdf",
        "pdf",
        "application/pdf",
        123L,
        Instant.parse("2026-09-04T10:00:00Z"),
        "laa-data-access",
        "checksum");
  }

  private UploadedDocument storedDocument(UUID submissionId, UUID documentId, String documentType) {
    PriorAuthorityDocument document = document(documentId, documentType);
    return UploadedDocument.builder()
        .documentId(documentId)
        .submissionId(submissionId)
        .originalFilename(document.fileName())
        .documentType(document.documentType())
        .metadata(UploadedDocumentMetadata.from(document))
        .build();
  }
}
