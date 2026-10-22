package uk.gov.justice.laa.dstew.access.testutils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.model.AutoGrantOutcome;
import uk.gov.justice.laa.dstew.access.model.AutoGrantedOutcomeRequest;
import uk.gov.justice.laa.dstew.access.model.DecisionStatus;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionProceedingRequest;
import uk.gov.justice.laa.dstew.access.model.MakeDecisionRequest;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionDetailsRequest;
import uk.gov.justice.laa.dstew.access.model.MeritsDecisionStatus;

public class GeneratedRequestFactory {
  private static final String REFERENCE_CHARACTERS = "0123456789ABCDEFHJKLMNPRTUVWXY";
  private final String runId;
  private final Set<String> generatedReferences = ConcurrentHashMap.newKeySet();

  public GeneratedRequestFactory(String runId) {
    this.runId = runId;
  }

  public ApplicationCreateRequest application(UUID applicationId, int index) {
    ApplicationCreateRequest baseline =
        ApplicationCreateRequestFixture.validCreateApplicationRequestWithRandomData(
            applicationId, proceedingId(applicationId));
    return ApplicationCreateRequest.builder()
        .id(applicationId)
        .status(baseline.getStatus())
        .laaReference(generateReference(index))
        .applicationContent(baseline.getApplicationContent())
        .build();
  }

  public MakeDecisionRequest decision(UUID proceedingId) {
    return decision(proceedingId, DecisionStatus.REFUSED);
  }

  public MakeDecisionRequest decision(UUID proceedingId, DecisionStatus decisionStatus) {
    return MakeDecisionRequest.builder()
        .overallDecision(decisionStatus)
        .proceedings(
            List.of(
                MakeDecisionProceedingRequest.builder()
                    .proceedingId(proceedingId)
                    .meritsDecision(
                        MeritsDecisionDetailsRequest.builder()
                            .decision(MeritsDecisionStatus.valueOf(decisionStatus.name()))
                            .reason("Mass-data " + decisionStatus.name().toLowerCase())
                            .justification(
                                "Mass-data generated " + decisionStatus.name().toLowerCase())
                            .build())
                    .build()))
        .applicationVersion(1L)
        .certificate(Map.of("source", "mass-data"))
        .build();
  }

  public AutoGrantedOutcomeRequest autoGrant() {
    return new AutoGrantedOutcomeRequest()
        .outcome(AutoGrantOutcome.AUTOGRANTED)
        .certificate(Map.of("source", "mass-data"));
  }

  private UUID proceedingId(UUID applicationId) {
    return UUID.nameUUIDFromBytes((applicationId + ":proceeding").getBytes(StandardCharsets.UTF_8));
  }

  private String generateReference(int index) {
    long seed =
        UUID.nameUUIDFromBytes((runId + ":" + index).getBytes(StandardCharsets.UTF_8))
            .getMostSignificantBits();
    Random random = new Random(seed);
    String reference;
    do {
      StringBuilder builder = new StringBuilder("L-");
      for (int characterIndex = 0; characterIndex < 6; characterIndex++) {
        if (characterIndex == 3) {
          builder.append('-');
        }
        builder.append(REFERENCE_CHARACTERS.charAt(random.nextInt(REFERENCE_CHARACTERS.length())));
      }
      reference = builder.toString();
    } while (!generatedReferences.add(reference));
    return reference;
  }
}
