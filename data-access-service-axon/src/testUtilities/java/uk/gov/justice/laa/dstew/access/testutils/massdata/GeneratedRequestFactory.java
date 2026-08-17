package uk.gov.justice.laa.dstew.access.testutils.massdata;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.model.AutoGrantOutcome;
import uk.gov.justice.laa.dstew.access.model.AutoGrantedOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.CaseworkerAssignRequest;
import uk.gov.justice.laa.dstew.access.model.CaseworkerUnassignRequest;
import uk.gov.justice.laa.dstew.access.model.DecisionStatus;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionProceedingRequest;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionRequest;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionDetailsRequest;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionStatus;
import uk.gov.justice.laa.dstew.access.testutils.ApplicationCreateRequestFixture;

public class GeneratedRequestFactory {
  private final String runId;

  public GeneratedRequestFactory(String runId) {
    this.runId = runId;
  }

  public ApplicationCreateRequest application(UUID applicationId, int index) {
    ApplicationCreateRequest baseline =
        ApplicationCreateRequestFixture.validCreateApplicationRequest(
            applicationId, proceedingId(applicationId));
    return ApplicationCreateRequest.builder()
        .applicationType(baseline.getApplicationType())
        .status(baseline.getStatus())
        .laaReference("AXON-MG-" + runId + "-" + index)
        .individuals(baseline.getIndividuals())
        .applicationContent(baseline.getApplicationContent())
        .build();
  }

  public MakeDecisionRequest decision(UUID proceedingId) {
    return MakeDecisionRequest.builder()
        .overallDecision(DecisionStatus.REFUSED)
        .proceedings(
            List.of(
                MakeDecisionProceedingRequest.builder()
                    .proceedingId(proceedingId)
                    .meritsDecision(
                        MeritsDecisionDetailsRequest.builder()
                            .decision(MeritsDecisionStatus.REFUSED)
                            .reason("Mass-data refusal")
                            .justification("Mass-data generated refusal")
                            .build())
                    .build()))
        .applicationVersion(0L)
        .certificate(Map.of("source", "mass-data"))
        .build();
  }

  public AutoGrantedOutcomeRequest autoGrant() {
    return new AutoGrantedOutcomeRequest()
        .outcome(AutoGrantOutcome.AUTOGRANTED)
        .certificate(Map.of("source", "mass-data"));
  }

  public CaseworkerAssignRequest assignment(UUID applicationId, UUID caseworkerId) {
    return new CaseworkerAssignRequest()
        .caseworkerId(caseworkerId)
        .applicationIds(List.of(applicationId));
  }

  public CaseworkerUnassignRequest unassignment() {
    return new CaseworkerUnassignRequest();
  }

  private UUID proceedingId(UUID applicationId) {
    return UUID.nameUUIDFromBytes((applicationId + ":proceeding").getBytes(StandardCharsets.UTF_8));
  }
}
