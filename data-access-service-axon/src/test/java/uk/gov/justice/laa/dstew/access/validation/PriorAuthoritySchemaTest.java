package uk.gov.justice.laa.dstew.access.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.justice.laa.dstew.access.content.priorauthority.Apportionment;
import uk.gov.justice.laa.dstew.access.content.priorauthority.BillingType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.DisbursementDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertCosts;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.content.priorauthority.TimeRequested;

class PriorAuthoritySchemaTest {

  private final JsonSchemaValidator validator = new JsonSchemaValidator();

  @Test
  void givenHourlyExpertPayload_whenValidate_thenAccepts() {
    validator.validate(hourlyExpertContent(), "PriorAuthority.json", 1);
  }

  @Test
  void givenFixedRateExpertPayload_whenValidate_thenAccepts() {
    validator.validate(
        new PriorAuthorityContent(
            "EXPERT",
            "Need expert assessment",
            new ExpertDetails(
                "Pathologist",
                "Casey Expert",
                "AB1 2CD",
                new ExpertCosts(
                    BillingType.FIXED_RATE, null, null, BigDecimal.valueOf(900), false, null)),
            null,
            null),
        "PriorAuthority.json",
        1);
  }

  @Test
  void givenCounselPayload_whenValidate_thenAccepts() {
    validator.validate(
        new PriorAuthorityContent(
            "COUNSEL",
            "Need specialist counsel",
            null,
            new CounselDetails(CounselType.KINGS_COUNSEL_ALONE),
            null),
        "PriorAuthority.json",
        1);
  }

  @Test
  void givenDisbursementPayload_whenValidate_thenAccepts() {
    validator.validate(
        new PriorAuthorityContent(
            "DISBURSEMENT",
            "Need interpreter costs",
            null,
            null,
            new DisbursementDetails("Interpreter", BigDecimal.valueOf(150.25))),
        "PriorAuthority.json",
        1);
  }

  @Test
  void givenExpertTypeWithoutExpertDetails_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent("EXPERT", "Need expert", null, null, null), "expertDetails");
  }

  @Test
  void givenHourlyWithoutHourlyRate_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent(
            "EXPERT",
            "Need expert",
            new ExpertDetails(
                "Pathologist",
                "Casey Expert",
                "AB1 2CD",
                new ExpertCosts(
                    BillingType.HOURLY,
                    null,
                    new TimeRequested(2, 30),
                    BigDecimal.valueOf(900),
                    false,
                    null)),
            null,
            null),
        "hourlyRate");
  }

  @Test
  void givenHourlyWithoutTimeRequested_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent(
            "EXPERT",
            "Need expert",
            new ExpertDetails(
                "Pathologist",
                "Casey Expert",
                "AB1 2CD",
                new ExpertCosts(
                    BillingType.HOURLY,
                    BigDecimal.valueOf(300),
                    null,
                    BigDecimal.valueOf(900),
                    false,
                    null)),
            null,
            null),
        "timeRequested");
  }

  @Test
  void givenSharedCostsWithoutApportionment_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent(
            "EXPERT",
            "Need expert",
            new ExpertDetails(
                "Pathologist",
                "Casey Expert",
                "AB1 2CD",
                new ExpertCosts(
                    BillingType.FIXED_RATE, null, null, BigDecimal.valueOf(900), true, null)),
            null,
            null),
        "apportionment");
  }

  @Test
  void givenInvalidCounselEnum_whenValidate_thenRejects() {
    Map<String, Object> payload =
        JsonMapper.builder()
            .build()
            .convertValue(hourlyExpertContent(), new TypeReference<Map<String, Object>>() {});
    payload.put("priorAuthorityType", "COUNSEL");
    payload.remove("expertDetails");
    payload.put("counselDetails", Map.of("counselType", "SILK"));

    assertRejected(payload, "counselType");
  }

  @Test
  void givenMissingDisbursementAmount_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent(
            "DISBURSEMENT",
            "Need disbursement",
            null,
            null,
            new DisbursementDetails("Interpreter", null)),
        "disbursementAmount");
  }

  @Test
  void givenInvalidType_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent("OTHER", "Need something", null, null, null),
        "priorAuthorityType");
  }

  @Test
  void givenMissingJustification_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent(
            "COUNSEL", null, null, new CounselDetails(CounselType.KINGS_COUNSEL_ALONE), null),
        "justification");
  }

  @Test
  void givenUnknownTopLevelField_whenValidate_thenRejects() {
    Map<String, Object> payload =
        new HashMap<>(
            JsonMapper.builder()
                .build()
                .convertValue(hourlyExpertContent(), new TypeReference<Map<String, Object>>() {}));
    payload.put("unexpectedField", "value");

    assertThatThrownBy(() -> validator.validate(payload, "PriorAuthority.json", 1))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            exception ->
                assertThat(((ValidationException) exception).errors())
                    .anyMatch(message -> message.toLowerCase().contains("additional")));
  }

  @Test
  void givenCounselTypeWithoutCounselDetails_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent("COUNSEL", "Need counsel", null, null, null), "counselDetails");
  }

  @Test
  void givenDisbursementTypeWithoutDisbursementDetails_whenValidate_thenRejects() {
    assertRejected(
        new PriorAuthorityContent("DISBURSEMENT", "Need disbursement", null, null, null),
        "disbursementDetails");
  }

  private void assertRejected(Object content, String expectedMessagePart) {
    assertThatThrownBy(() -> validator.validate(content, "PriorAuthority.json", 1))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            exception ->
                assertThat(((ValidationException) exception).errors())
                    .anyMatch(message -> message.contains(expectedMessagePart)));
  }

  private PriorAuthorityContent hourlyExpertContent() {
    return new PriorAuthorityContent(
        "EXPERT",
        "Need expert assessment",
        new ExpertDetails(
            "Pathologist",
            "Casey Expert",
            "AB1 2CD",
            new ExpertCosts(
                BillingType.HOURLY,
                BigDecimal.valueOf(300),
                new TimeRequested(2, 30),
                BigDecimal.valueOf(900),
                true,
                new Apportionment(2, BigDecimal.valueOf(450)))),
        null,
        null);
  }
}
