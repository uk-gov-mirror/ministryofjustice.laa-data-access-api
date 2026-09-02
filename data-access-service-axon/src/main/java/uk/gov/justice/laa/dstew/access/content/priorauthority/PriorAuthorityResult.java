package uk.gov.justice.laa.dstew.access.content.priorauthority;

import java.util.UUID;
import uk.gov.justice.laa.dstew.access.query.application.priorauthority.PriorAuthorityReadModel;

/** Typed result of retrieving a prior-authority submission. */
public record PriorAuthorityResult(
    UUID priorAuthorityId,
    UUID applicationId,
    String justification,
    String status,
    PriorAuthorityType priorAuthorityType,
    ExpertDetails expertDetails,
    CounselDetails counselDetails,
    DisbursementDetails disbursementDetails) {

  /** Builds the use-case result from the current-state projection and versioned content. */
  public static PriorAuthorityResult from(
      PriorAuthorityReadModel priorAuthority, PriorAuthorityContent content) {
    PriorAuthorityType priorAuthorityType = PriorAuthorityType.from(content.priorAuthorityType());
    return new PriorAuthorityResult(
        priorAuthority.getSubmissionId(),
        priorAuthority.getApplicationId(),
        content.justification(),
        priorAuthority.getStatus(),
        priorAuthorityType,
        priorAuthorityType == PriorAuthorityType.EXPERT ? toExpertDetails(content) : null,
        priorAuthorityType == PriorAuthorityType.COUNSEL ? toCounselDetails(content) : null,
        priorAuthorityType == PriorAuthorityType.DISBURSEMENT
            ? toDisbursementDetails(content)
            : null);
  }

  private static ExpertDetails toExpertDetails(PriorAuthorityContent content) {
    if (content.expertDetails() == null) {
      return null;
    }
    var expertCosts = content.expertDetails().expertCosts();
    return new ExpertDetails(
        content.expertDetails().expertType(),
        content.expertDetails().expertFullName(),
        content.expertDetails().expertPostcode(),
        expertCosts);
  }

  private static CounselDetails toCounselDetails(PriorAuthorityContent content) {
    return content.counselDetails() == null
        ? null
        : new CounselDetails(content.counselDetails().counselType());
  }

  private static DisbursementDetails toDisbursementDetails(PriorAuthorityContent content) {
    return content.disbursementDetails() == null ? null : content.disbursementDetails();
  }
}
