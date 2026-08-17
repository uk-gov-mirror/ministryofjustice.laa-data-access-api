package uk.gov.justice.laa.dstew.access.testutils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.datafaker.Faker;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.model.ApplicationStatus;
import uk.gov.justice.laa.dstew.access.model.ApplicationType;
import uk.gov.justice.laa.dstew.access.model.IndividualCreateRequest;
import uk.gov.justice.laa.dstew.access.model.IndividualType;

/** Builds valid API requests shared by fast and Postgres integration tests. */
public final class ApplicationCreateRequestFixture {

  private ApplicationCreateRequestFixture() {}

  /** Creates a valid request using the supplied Apply identifiers. */
  public static ApplicationCreateRequest validCreateApplicationRequest(
      UUID applyApplicationId, UUID applyProceedingId) {
    Map<String, Object> content =
        Map.of(
            "id",
            applyApplicationId.toString(),
            "submittedAt",
            "2026-07-14T12:30:00Z",
            "office",
            Map.of("code", "1A001B"),
            "applicant",
            Map.of(
                "id",
                UUID.randomUUID().toString(),
                "addresses",
                List.of(Map.of("id", UUID.randomUUID().toString()))),
            "proceedings",
            List.of(
                Map.of(
                    "id",
                    applyProceedingId.toString(),
                    "leadProceeding",
                    true,
                    "description",
                    "Care order",
                    "categoryOfLawEnum",
                    "FAMILY",
                    "matterTypeEnum",
                    "SPECIAL_CHILDREN_ACT",
                    "usedDelegatedFunctions",
                    false)));

    IndividualCreateRequest individual =
        IndividualCreateRequest.builder()
            .firstName("Ada")
            .lastName("Lovelace")
            .dateOfBirth(LocalDate.of(1815, 12, 10))
            .details(Map.of("preferredName", "Ada"))
            .type(IndividualType.CLIENT)
            .build();

    return ApplicationCreateRequest.builder()
        .applicationType(ApplicationType.APPLY)
        .status(ApplicationStatus.APPLICATION_SUBMITTED)
        .applicationContent(content)
        .laaReference("LAA-123")
        .individuals(List.of(individual))
        .build();
  }
  /** Creates a valid request using the supplied Apply identifiers. */
  public static ApplicationCreateRequest validCreateApplicationRequestWithRandomData(
      UUID applyApplicationId, UUID applyProceedingId) {

    Faker faker = new Faker();
    Map<String, Object> content =
        Map.of(
            "id",
            applyApplicationId.toString(),
            "submittedAt",
            "2026-07-14T12:30:00Z",
            "office",
            Map.of("code", "1A001B"),
            "applicant",
            Map.of(
                "id",
                UUID.randomUUID().toString(),
                "addresses",
                List.of(Map.of("id", UUID.randomUUID().toString()))),
            "proceedings",
            List.of(
                Map.of(
                    "id",
                    applyProceedingId.toString(),
                    "leadProceeding",
                    true,
                    "description",
                    "Care order",
                    "categoryOfLawEnum",
                    "FAMILY",
                    "matterTypeEnum",
                    "SPECIAL_CHILDREN_ACT",
                    "usedDelegatedFunctions",
                    false)));

    String firstName = faker.name().firstName();
    IndividualCreateRequest individual =
        IndividualCreateRequest.builder()
            .firstName(firstName)
            .lastName(faker.name().lastName())
            .dateOfBirth(LocalDate.of(1815, 12, 10))
            .details(Map.of("preferredName", firstName))
            .type(IndividualType.CLIENT)
            .build();

    return ApplicationCreateRequest.builder()
        .applicationType(ApplicationType.APPLY)
        .status(ApplicationStatus.APPLICATION_SUBMITTED)
        .applicationContent(content)
        .laaReference("LAA-123")
        .individuals(List.of(individual))
        .build();
  }



  /** Creates a valid request that links the new Application to an existing Apply Application. */
  public static ApplicationCreateRequest validLinkedCreateApplicationRequest(
      UUID applyApplicationId, UUID applyProceedingId, UUID leadApplyApplicationId) {
    return validLinkedCreateApplicationRequest(
        applyApplicationId, applyProceedingId, leadApplyApplicationId, applyApplicationId);
  }

  /** Creates a valid request with an explicitly declared linked Application group member. */
  public static ApplicationCreateRequest validLinkedCreateApplicationRequest(
      UUID applyApplicationId,
      UUID applyProceedingId,
      UUID leadApplyApplicationId,
      UUID associatedApplyApplicationId) {
    ApplicationCreateRequest request =
        validCreateApplicationRequest(applyApplicationId, applyProceedingId);
    Map<String, Object> content = new HashMap<>(request.getApplicationContent());
    content.put(
        "allLinkedApplications",
        List.of(
            Map.of(
                "leadApplicationId", leadApplyApplicationId.toString(),
                "associatedApplicationId", associatedApplyApplicationId.toString())));

    return ApplicationCreateRequest.builder()
        .applicationType(request.getApplicationType())
        .status(request.getStatus())
        .applicationContent(content)
        .laaReference(request.getLaaReference())
        .individuals(request.getIndividuals())
        .build();
  }
}
