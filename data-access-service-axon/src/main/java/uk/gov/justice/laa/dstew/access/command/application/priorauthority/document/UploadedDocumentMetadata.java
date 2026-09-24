package uk.gov.justice.laa.dstew.access.command.application.priorauthority.document;

import java.time.Instant;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityDocument;

/** JSON-backed attributes of an uploaded document that are not query columns. */
public record UploadedDocumentMetadata(
    String fileType,
    String contentType,
    Long fileSize,
    Instant uploadedAt,
    String sourceService,
    String checksum) {

  /** Creates metadata from the domain document representation stored in draft content. */
  public static UploadedDocumentMetadata from(PriorAuthorityDocument document) {
    return new UploadedDocumentMetadata(
        document.fileType(),
        document.mediaType(),
        document.size(),
        document.uploadedAt(),
        document.sourceService(),
        document.checksum());
  }

  /** Rebuilds the domain document view from stored metadata and relational columns. */
  public PriorAuthorityDocument toDocument(
      java.util.UUID documentId, String originalFilename, String documentType) {
    return new PriorAuthorityDocument(
        documentId,
        documentType,
        originalFilename,
        fileType,
        contentType,
        fileSize,
        uploadedAt,
        sourceService,
        checksum);
  }
}
