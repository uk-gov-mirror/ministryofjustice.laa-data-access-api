package uk.gov.justice.laa.dstew.access.command.application.priorauthority.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Query-side metadata for a file uploaded to a prior-authority submission. */
@Entity
@Table(name = "uploaded_documents")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadedDocument {

  @Id
  @Column(name = "document_id")
  private UUID documentId;

  @Column(name = "submission_id", nullable = false)
  private UUID submissionId;

  @Column(name = "original_filename", nullable = false)
  private String originalFilename;

  @Column(name = "document_type")
  private String documentType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private UploadedDocumentMetadata metadata;
}
