package uk.gov.justice.laa.dstew.access.controller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.CreatePriorAuthorityCommand;
import uk.gov.justice.laa.dstew.access.model.Apportionment;
import uk.gov.justice.laa.dstew.access.model.BillingType;
import uk.gov.justice.laa.dstew.access.model.CounselType;
import uk.gov.justice.laa.dstew.access.model.CreatePriorAuthorityRequest;
import uk.gov.justice.laa.dstew.access.model.PriorAuthorityType;
import uk.gov.justice.laa.dstew.access.model.TimeRequested;

class CreatePriorAuthorityCommandMapperTest {

  private final CreatePriorAuthorityCommandMapper mapper =
      new CreatePriorAuthorityCommandMapper(JsonMapper.builder().build());

  @Test
  void givenExpertRequest_whenMapped_thenMapsAllExpertFields() {
    CreatePriorAuthorityCommand command = mapper.toCommand(UUID.randomUUID(), expertRequest());

    assertThat(command.content().priorAuthorityType()).isEqualTo("EXPERT");
    assertThat(command.content().justification()).isEqualTo("Need expert assessment");
    assertThat(command.content().expertDetails()).isNotNull();
    assertThat(command.content().expertDetails().expertType()).isEqualTo("Pathologist");
    assertThat(command.content().expertDetails().expertFullName()).isEqualTo("Casey Expert");
    assertThat(command.content().expertDetails().expertPostcode()).isEqualTo("AB1 2CD");
    assertThat(command.content().expertDetails().expertCosts()).isNotNull();
    assertThat(command.content().expertDetails().expertCosts().billingType().name())
        .isEqualTo("HOURLY");
    assertThat(command.content().expertDetails().expertCosts().hourlyRate())
        .isEqualByComparingTo(BigDecimal.valueOf(300.0));
    assertThat(command.content().expertDetails().expertCosts().timeRequested()).isNotNull();
    assertThat(command.content().expertDetails().expertCosts().timeRequested().hours())
        .isEqualTo(2);
    assertThat(command.content().expertDetails().expertCosts().timeRequested().minutes())
        .isEqualTo(30);
    assertThat(command.content().expertDetails().expertCosts().totalAmount())
        .isEqualByComparingTo(BigDecimal.valueOf(900.0));
    assertThat(command.content().expertDetails().expertCosts().costsSharedWithOtherParties())
        .isTrue();
    assertThat(command.content().expertDetails().expertCosts().apportionment()).isNotNull();
    assertThat(
            command.content().expertDetails().expertCosts().apportionment().partiesSharingCosts())
        .isEqualTo(2);
    assertThat(command.content().expertDetails().expertCosts().apportionment().clientShareAmount())
        .isEqualByComparingTo(BigDecimal.valueOf(450.0));
  }

  @Test
  void givenCounselRequest_whenMapped_thenMapsCounselDetails() {
    CreatePriorAuthorityCommand command = mapper.toCommand(UUID.randomUUID(), counselRequest());

    assertThat(command.content().priorAuthorityType()).isEqualTo("COUNSEL");
    assertThat(command.content().counselDetails()).isNotNull();
    assertThat(command.content().counselDetails().counselType().name())
        .isEqualTo("KINGS_COUNSEL_ALONE");
  }

  @Test
  void givenDisbursementRequest_whenMapped_thenMapsDisbursementDetails() {
    CreatePriorAuthorityCommand command =
        mapper.toCommand(UUID.randomUUID(), disbursementRequest());

    assertThat(command.content().priorAuthorityType()).isEqualTo("DISBURSEMENT");
    assertThat(command.content().disbursementDetails()).isNotNull();
    assertThat(command.content().disbursementDetails().disbursementPurpose())
        .isEqualTo("Interpreter");
    assertThat(command.content().disbursementDetails().disbursementAmount())
        .isEqualByComparingTo(BigDecimal.valueOf(150.25));
  }

  @Test
  void givenRequest_whenMapped_thenSchemaMetadataIsVersion1AndPriorAuthorityJson() {
    CreatePriorAuthorityCommand command = mapper.toCommand(UUID.randomUUID(), expertRequest());

    assertThat(command.schemaVersion()).isEqualTo(1);
    assertThat(command.schemaName()).isEqualTo("PriorAuthority.json");
  }

  @Test
  void givenRequest_whenMapped_thenTimestampIsNonNull() {
    CreatePriorAuthorityCommand command = mapper.toCommand(UUID.randomUUID(), expertRequest());

    assertThat(command.occurredAt()).isNotNull();
  }

  @Test
  void givenRequest_whenMapped_thenContentIsSerialised() {
    CreatePriorAuthorityCommand command = mapper.toCommand(UUID.randomUUID(), expertRequest());

    assertThat(command.serialisedRequest()).contains("Need expert assessment");
    assertThatCode(() -> JsonMapper.builder().build().readTree(command.serialisedRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void givenSerializationFailure_whenMapped_thenWrapsInIllegalStateException() throws Exception {
    ObjectMapper objectMapper = mock(ObjectMapper.class);
    CreatePriorAuthorityRequest request = expertRequest();
    CreatePriorAuthorityCommandMapper failingMapper =
        new CreatePriorAuthorityCommandMapper(objectMapper);

    when(objectMapper.writeValueAsString(request)).thenThrow(new JacksonException("boom") {});

    assertThatThrownBy(() -> failingMapper.toCommand(UUID.randomUUID(), request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unable to serialise CreatePriorAuthorityRequest")
        .hasCauseInstanceOf(JacksonException.class);
  }

  @Test
  void givenNullExpertCosts_whenMapped_thenExpertCostsIsNull() {
    CreatePriorAuthorityCommand command =
        mapper.toCommand(
            UUID.randomUUID(),
            CreatePriorAuthorityRequest.builder()
                .priorAuthorityType(PriorAuthorityType.EXPERT)
                .expertDetails(
                    uk.gov.justice.laa.dstew.access.model.ExpertDetails.builder()
                        .expertType("Forensic Accountant")
                        .build())
                .build());

    assertThat(command.content().expertDetails().expertCosts()).isNull();
  }

  @Test
  void givenExpertCostsWithNullSubFields_whenMapped_thenNullsPreserved() {
    CreatePriorAuthorityCommand command =
        mapper.toCommand(
            UUID.randomUUID(),
            CreatePriorAuthorityRequest.builder()
                .priorAuthorityType(PriorAuthorityType.EXPERT)
                .expertDetails(
                    uk.gov.justice.laa.dstew.access.model.ExpertDetails.builder()
                        .expertType("Forensic Accountant")
                        .expertCosts(
                            uk.gov.justice.laa.dstew.access.model.ExpertCosts.builder()
                                .billingType(null)
                                .hourlyRate(null)
                                .timeRequested(null)
                                .totalAmount(null)
                                .apportionment(null)
                                .build())
                        .build())
                .build());

    assertThat(command.content().expertDetails().expertCosts().billingType()).isNull();
    assertThat(command.content().expertDetails().expertCosts().hourlyRate()).isNull();
    assertThat(command.content().expertDetails().expertCosts().timeRequested()).isNull();
    assertThat(command.content().expertDetails().expertCosts().apportionment()).isNull();
  }

  private CreatePriorAuthorityRequest expertRequest() {
    return CreatePriorAuthorityRequest.builder()
        .priorAuthorityType(PriorAuthorityType.EXPERT)
        .justification("Need expert assessment")
        .expertDetails(
            uk.gov.justice.laa.dstew.access.model.ExpertDetails.builder()
                .expertType("Pathologist")
                .expertFullName("Casey Expert")
                .expertPostcode("AB1 2CD")
                .expertCosts(
                    uk.gov.justice.laa.dstew.access.model.ExpertCosts.builder()
                        .billingType(BillingType.HOURLY)
                        .hourlyRate(300.0)
                        .timeRequested(TimeRequested.builder().hours(2).minutes(30).build())
                        .totalAmount(900.0)
                        .costsSharedWithOtherParties(true)
                        .apportionment(
                            Apportionment.builder()
                                .partiesSharingCosts(2)
                                .clientShareAmount(450.0)
                                .build())
                        .build())
                .build())
        .build();
  }

  private CreatePriorAuthorityRequest counselRequest() {
    return CreatePriorAuthorityRequest.builder()
        .priorAuthorityType(PriorAuthorityType.COUNSEL)
        .justification("Need specialist counsel")
        .counselDetails(
            uk.gov.justice.laa.dstew.access.model.CounselDetails.builder()
                .counselType(CounselType.KINGS_COUNSEL_ALONE)
                .build())
        .build();
  }

  private CreatePriorAuthorityRequest disbursementRequest() {
    return CreatePriorAuthorityRequest.builder()
        .priorAuthorityType(PriorAuthorityType.DISBURSEMENT)
        .justification("Need interpreter costs")
        .disbursementDetails(
            uk.gov.justice.laa.dstew.access.model.DisbursementDetails.builder()
                .disbursementPurpose("Interpreter")
                .disbursementAmount(150.25)
                .build())
        .build();
  }
}
