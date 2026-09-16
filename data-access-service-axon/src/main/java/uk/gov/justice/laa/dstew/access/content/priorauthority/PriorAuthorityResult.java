package uk.gov.justice.laa.dstew.access.content.priorauthority;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.With;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.query.application.priorauthority.PriorAuthorityReadModel;

/** Typed result of retrieving a prior-authority submission. */
@Builder
@With
public record PriorAuthorityResult(
    UUID priorAuthorityId,
    UUID applicationId,
    String justification,
    String status,
    PriorAuthorityType priorAuthorityType,
    ExpertDetails expertDetails,
    CounselDetails counselDetails,
    DisbursementDetails disbursementDetails,
    List<PriorAuthorityDocument> uploadedDocuments,
    PriorAuthorityDataPayload.DecisionDetails decisionDetails) {

  /** Builds the use-case result from the current-state projection, versioned content and status. */
  public static PriorAuthorityResult from(
      PriorAuthorityReadModel priorAuthority, PriorAuthorityDataPayload payload, String status) {
    return build(
        priorAuthority.getPriorAuthorityId(),
        priorAuthority.getApplicationId(),
        status,
        payload.content(),
        payload.decisionDetails());
  }

  /**
   * Builds the use-case result for an in-progress draft, whose content may be partial since it has
   * not yet been schema-validated.
   */
  public static PriorAuthorityResult fromDraft(PriorAuthorityDataPayload payload) {
    return build(
        payload.priorAuthorityId(),
        payload.applicationId(),
        PriorAuthorityStatus.DRAFT.name(),
        payload.content(),
        payload.decisionDetails());
  }

  private static PriorAuthorityResult build(
      UUID priorAuthorityId,
      UUID applicationId,
      String status,
      PriorAuthorityContent content,
      PriorAuthorityDataPayload.DecisionDetails decisionDetails) {
    PriorAuthorityType priorAuthorityType = content.priorAuthorityType();
    return new PriorAuthorityResult(
        priorAuthorityId,
        applicationId,
        content.justification(),
        status,
        priorAuthorityType,
        priorAuthorityType == PriorAuthorityType.EXPERT ? toExpertDetails(content) : null,
        priorAuthorityType == PriorAuthorityType.COUNSEL ? toCounselDetails(content) : null,
        priorAuthorityType == PriorAuthorityType.DISBURSEMENT
            ? toDisbursementDetails(content)
            : null,
        content.uploadedDocuments(),
        decisionDetails);
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
