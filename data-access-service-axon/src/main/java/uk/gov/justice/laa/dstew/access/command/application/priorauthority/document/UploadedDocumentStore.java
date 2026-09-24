package uk.gov.justice.laa.dstew.access.command.application.priorauthority.document;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityDocument;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;

/** Stores metadata independently of the prior-authority draft payload. */
@Component
public class UploadedDocumentStore {

  private final UploadedDocumentRepository repository;

  public UploadedDocumentStore(UploadedDocumentRepository repository) {
    this.repository = repository;
  }

  /** Persists metadata for a newly uploaded prior-authority document. */
  public void save(UUID submissionId, PriorAuthorityDocument document) {
    repository.saveAndFlush(
        UploadedDocument.builder()
            .documentId(document.documentId())
            .submissionId(submissionId)
            .originalFilename(document.fileName())
            .documentType(document.documentType())
            .metadata(UploadedDocumentMetadata.from(document))
            .build());
  }

  /** Replaces the stored document type for the identified prior-authority document. */
  public void updateDocumentType(UUID submissionId, UUID documentId, String documentType) {
    UploadedDocument document =
        repository
            .findById(documentId)
            .filter(existing -> existing.getSubmissionId().equals(submissionId))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Document %s not found for Prior Authority %s"
                            .formatted(documentId, submissionId)));
    save(
        submissionId,
        document
            .getMetadata()
            .toDocument(documentId, document.getOriginalFilename(), documentType));
  }

  /** Deletes the stored metadata row for the identified prior-authority document. */
  public void delete(UUID submissionId, UUID documentId) {
    UploadedDocument document =
        repository
            .findById(documentId)
            .filter(existing -> existing.getSubmissionId().equals(submissionId))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Document %s not found for Prior Authority %s"
                            .formatted(documentId, submissionId)));
    repository.delete(document);
  }

  /** Returns stored documents in the same order as the requested document identifiers. */
  public List<PriorAuthorityDocument> findAllInOrder(Collection<UUID> documentIds) {
    Map<UUID, UploadedDocument> documentsById =
        repository.findAllById(documentIds).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    UploadedDocument::getDocumentId, Function.identity()));
    return documentIds.stream()
        .map(documentsById::get)
        .filter(java.util.Objects::nonNull)
        .map(
            document ->
                document
                    .getMetadata()
                    .toDocument(
                        document.getDocumentId(),
                        document.getOriginalFilename(),
                        document.getDocumentType()))
        .toList();
  }
}
