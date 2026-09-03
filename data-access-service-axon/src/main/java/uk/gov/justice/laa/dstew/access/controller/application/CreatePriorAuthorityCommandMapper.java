package uk.gov.justice.laa.dstew.access.controller.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.CreatePriorAuthorityCommand;
import uk.gov.justice.laa.dstew.access.content.priorauthority.Apportionment;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.DisbursementDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertCosts;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.content.priorauthority.TimeRequested;
import uk.gov.justice.laa.dstew.access.model.CreatePriorAuthorityRequest;

/** Maps the generated HTTP request model to the Axon create-prior-authority command. */
@Component
public class CreatePriorAuthorityCommandMapper {

  private final ObjectMapper objectMapper;

  public CreatePriorAuthorityCommandMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Creates a create-prior-authority command with server-generated submission metadata. */
  public CreatePriorAuthorityCommand toCommand(
      UUID applicationId, CreatePriorAuthorityRequest request) {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityContent content = toContent(request);
    return new CreatePriorAuthorityCommand(
        submissionId,
        applicationId,
        content,
        serialise(request),
        1,
        "PriorAuthority.json",
        Instant.now());
  }

  private PriorAuthorityContent toContent(CreatePriorAuthorityRequest request) {
    return new PriorAuthorityContent(
        enumName(request.getPriorAuthorityType()),
        request.getJustification(),
        toExpertDetails(request.getExpertDetails()),
        toCounselDetails(request.getCounselDetails()),
        toDisbursementDetails(request.getDisbursementDetails()),
        List.of());
  }

  private ExpertDetails toExpertDetails(
      uk.gov.justice.laa.dstew.access.model.ExpertDetails details) {
    if (details == null) {
      return null;
    }

    return new ExpertDetails(
        details.getExpertType(),
        details.getExpertFullName(),
        details.getExpertPostcode(),
        toExpertCosts(details.getExpertCosts()));
  }

  private ExpertCosts toExpertCosts(uk.gov.justice.laa.dstew.access.model.ExpertCosts costs) {
    if (costs == null) {
      return null;
    }

    return new ExpertCosts(
        enumName(costs.getBillingType()),
        toBigDecimal(costs.getHourlyRate()),
        toTimeRequested(costs.getTimeRequested()),
        toBigDecimal(costs.getTotalAmount()),
        costs.getCostsSharedWithOtherParties(),
        toApportionment(costs.getApportionment()));
  }

  private TimeRequested toTimeRequested(uk.gov.justice.laa.dstew.access.model.TimeRequested time) {
    if (time == null) {
      return null;
    }

    return new TimeRequested(time.getHours(), time.getMinutes());
  }

  private Apportionment toApportionment(
      uk.gov.justice.laa.dstew.access.model.Apportionment apportionment) {
    if (apportionment == null) {
      return null;
    }

    return new Apportionment(
        apportionment.getPartiesSharingCosts(), toBigDecimal(apportionment.getClientShareAmount()));
  }

  private CounselDetails toCounselDetails(
      uk.gov.justice.laa.dstew.access.model.CounselDetails details) {
    if (details == null) {
      return null;
    }

    return new CounselDetails(enumName(details.getCounselType()));
  }

  private DisbursementDetails toDisbursementDetails(
      uk.gov.justice.laa.dstew.access.model.DisbursementDetails details) {
    if (details == null) {
      return null;
    }

    return new DisbursementDetails(
        details.getDisbursementPurpose(), toBigDecimal(details.getDisbursementAmount()));
  }

  private BigDecimal toBigDecimal(Double value) {
    if (value == null) {
      return null;
    }

    return BigDecimal.valueOf(value);
  }

  private String enumName(Enum<?> value) {
    if (value == null) {
      return null;
    }

    return value.name();
  }

  private String serialise(CreatePriorAuthorityRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to serialise CreatePriorAuthorityRequest", exception);
    }
  }
}
