package uk.gov.justice.laa.dstew.access.controller.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.justice.laa.dstew.access.content.priorauthority.Apportionment;
import uk.gov.justice.laa.dstew.access.content.priorauthority.BillingType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.DisbursementDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertCosts;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.TimeRequested;

class GetPriorAuthorityResponseMapperTest {

  private final GetPriorAuthorityResponseMapper mapper = new GetPriorAuthorityResponseMapper();

  @Test
  void givenExpertResult_whenMapped_thenConvertsUseCaseTypesToGeneratedApiTypes() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityResult result =
        new PriorAuthorityResult(
            priorAuthorityId,
            applicationId,
            "Expert is required",
            "PENDING",
            PriorAuthorityType.EXPERT,
            new ExpertDetails(
                "PSYCHIATRIST",
                "Jane Doe",
                "AB1 2CD",
                new ExpertCosts(
                    BillingType.HOURLY,
                    BigDecimal.valueOf(150),
                    new TimeRequested(2, 30),
                    BigDecimal.valueOf(300),
                    true,
                    new Apportionment(2, BigDecimal.valueOf(150)))),
            null,
            null);

    var response = mapper.toResponse(result);

    assertThat(response.getPriorAuthorityId()).isEqualTo(priorAuthorityId);
    assertThat(response.getApplicationId()).isEqualTo(applicationId);
    assertThat(response.getPriorAuthorityType().getValue()).isEqualTo("EXPERT");
    assertThat(response.getStatus()).isEqualTo("PENDING");
    assertThat(response.getExpertDetails().getExpertCosts().getBillingType().getValue())
        .isEqualTo("HOURLY");
    assertThat(response.getExpertDetails().getExpertCosts().getHourlyRate()).isEqualTo(150.0);
    assertThat(response.getExpertDetails().getExpertCosts().getTimeRequested().getHours())
        .isEqualTo(2);
    assertThat(response.getExpertDetails().getExpertCosts().getTimeRequested().getMinutes())
        .isEqualTo(30);
    assertThat(
            response.getExpertDetails().getExpertCosts().getApportionment().getClientShareAmount())
        .isEqualTo(150.0);
  }

  @Test
  void givenCounselAndDisbursementResults_whenMapped_thenMapsTheirDetails() {
    PriorAuthorityResult counsel =
        new PriorAuthorityResult(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Counsel is required",
            "PENDING",
            PriorAuthorityType.COUNSEL,
            null,
            new CounselDetails(CounselType.TWO_JUNIOR_COUNSEL),
            null);
    PriorAuthorityResult disbursement =
        new PriorAuthorityResult(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Travel is required",
            "PENDING",
            PriorAuthorityType.DISBURSEMENT,
            null,
            null,
            new DisbursementDetails("Travel", BigDecimal.TEN));

    var counselResponse = mapper.toResponse(counsel);
    var disbursementResponse = mapper.toResponse(disbursement);

    assertThat(counselResponse.getCounselDetails().getCounselType().getValue())
        .isEqualTo("TWO_JUNIOR_COUNSEL");
    assertThat(disbursementResponse.getDisbursementDetails().getDisbursementAmount())
        .isEqualTo(10.0);
  }

  @Test
  void givenResultWithOptionalValuesAbsent_whenMapped_thenLeavesApiValuesNull() {
    PriorAuthorityResult result =
        new PriorAuthorityResult(
            UUID.randomUUID(), UUID.randomUUID(), "", null, null, null, null, null);

    var response = mapper.toResponse(result);

    assertThat(response.getPriorAuthorityType()).isNull();
    assertThat(response.getExpertDetails()).isNull();
    assertThat(response.getCounselDetails()).isNull();
    assertThat(response.getDisbursementDetails()).isNull();
  }

  @Test
  void givenNullableNestedValues_whenMapped_thenLeavesTheirApiValuesNull() {
    PriorAuthorityResult expert =
        new PriorAuthorityResult(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Expert is required",
            "PENDING",
            PriorAuthorityType.EXPERT,
            new ExpertDetails(
                "PSYCHIATRIST",
                "Jane Doe",
                "AB1 2CD",
                new ExpertCosts(null, null, null, null, null, null)),
            null,
            null);
    PriorAuthorityResult counsel =
        new PriorAuthorityResult(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Counsel is required",
            "PENDING",
            PriorAuthorityType.COUNSEL,
            null,
            new CounselDetails(null),
            null);
    PriorAuthorityResult disbursement =
        new PriorAuthorityResult(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Disbursement is required",
            "PENDING",
            PriorAuthorityType.DISBURSEMENT,
            null,
            null,
            new DisbursementDetails("Travel", null));

    var expertResponse = mapper.toResponse(expert);
    var counselResponse = mapper.toResponse(counsel);
    var disbursementResponse = mapper.toResponse(disbursement);

    assertThat(expertResponse.getExpertDetails().getExpertCosts().getBillingType()).isNull();
    assertThat(expertResponse.getExpertDetails().getExpertCosts().getHourlyRate()).isNull();
    assertThat(expertResponse.getExpertDetails().getExpertCosts().getTimeRequested()).isNull();
    assertThat(expertResponse.getExpertDetails().getExpertCosts().getTotalAmount()).isNull();
    assertThat(expertResponse.getExpertDetails().getExpertCosts().getApportionment()).isNull();
    assertThat(counselResponse.getCounselDetails().getCounselType()).isNull();
    assertThat(disbursementResponse.getDisbursementDetails().getDisbursementAmount()).isNull();
  }
}
