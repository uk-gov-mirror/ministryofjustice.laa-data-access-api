package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import uk.gov.justice.laa.dstew.access.ExcludeFromGeneratedCodeCoverage;

/** Replayable current-state read model for a prior-authority submission. */
@Entity
@Table(name = "prior_authority_current_state")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ExcludeFromGeneratedCodeCoverage
public class PriorAuthorityReadModel {

  @Id
  @Column(name = "prior_authority_id")
  private UUID priorAuthorityId;

  @Column(name = "application_id")
  private UUID applicationId;

  @Column(name = "data_version")
  private long dataVersion;

  private String status;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "modified_at")
  private Instant modifiedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "uploaded_document_ids", nullable = false)
  private List<UUID> uploadedDocumentIds;
}
