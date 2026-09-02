package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataStore;
import uk.gov.justice.laa.dstew.access.content.priorauthority.BillingType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.DisbursementDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertCosts;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityType;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;

class GetPriorAuthorityUseCaseTest {

  private PriorAuthorityDataStore dataStore;
  private QueryGateway queryGateway;
  private GetPriorAuthorityUseCase useCase;

  @BeforeEach
  void setUp() {
    dataStore = org.mockito.Mockito.mock(PriorAuthorityDataStore.class);
    queryGateway = org.mockito.Mockito.mock(QueryGateway.class);
    useCase = new GetPriorAuthorityUseCase(dataStore, queryGateway);
  }

  @Test
  void givenCounselPriorAuthority_whenRetrieved_thenHydratesStoredDataVersion() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityReadModel readModel =
        PriorAuthorityReadModel.builder()
            .submissionId(submissionId)
            .applicationId(applicationId)
            .dataVersion(4L)
            .status("PENDING")
            .build();
    PriorAuthorityContent content =
        new PriorAuthorityContent(
            "COUNSEL",
            "Counsel is required",
            null,
            new CounselDetails(CounselType.TWO_JUNIOR_COUNSEL),
            null);
    when(queryGateway.query(
            any(FindPriorAuthorityBySubmissionIdQuery.class), eq(PriorAuthorityReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(readModel));
    when(dataStore.get(submissionId, 4L))
        .thenReturn(
            new PriorAuthorityDataPayload(
                submissionId, applicationId, content, "{}", Instant.parse("2026-08-26T10:00:00Z")));

    PriorAuthorityResult response = useCase.getPriorAuthority(submissionId);

    assertThat(response.priorAuthorityId()).isEqualTo(submissionId);
    assertThat(response.applicationId()).isEqualTo(applicationId);
    assertThat(response.priorAuthorityType()).isEqualTo(PriorAuthorityType.COUNSEL);
    assertThat(response.justification()).isEqualTo("Counsel is required");
    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(response.counselDetails().counselType()).isEqualTo(CounselType.TWO_JUNIOR_COUNSEL);
    assertThat(response.expertDetails()).isNull();
    assertThat(response.disbursementDetails()).isNull();
    verify(dataStore).get(submissionId, 4L);
  }

  @ParameterizedTest
  @MethodSource("supportedPriorAuthorityTypes")
  void givenSupportedPriorAuthorityType_whenRetrieved_thenHydratesOnlyMatchingDetails(
      PriorAuthorityContent content,
      PriorAuthorityType expectedType,
      boolean hasExpertDetails,
      boolean hasCounselDetails,
      boolean hasDisbursementDetails) {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityReadModel readModel =
        PriorAuthorityReadModel.builder().submissionId(submissionId).dataVersion(1L).build();
    when(queryGateway.query(
            any(FindPriorAuthorityBySubmissionIdQuery.class), eq(PriorAuthorityReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(readModel));
    when(dataStore.get(submissionId, 1L))
        .thenReturn(
            new PriorAuthorityDataPayload(submissionId, null, content, "{}", Instant.now()));

    PriorAuthorityResult response = useCase.getPriorAuthority(submissionId);

    assertThat(response.priorAuthorityType()).isEqualTo(expectedType);
    assertThat(response.expertDetails() != null).isEqualTo(hasExpertDetails);
    assertThat(response.counselDetails() != null).isEqualTo(hasCounselDetails);
    assertThat(response.disbursementDetails() != null).isEqualTo(hasDisbursementDetails);
  }

  private static Stream<Arguments> supportedPriorAuthorityTypes() {
    return Stream.of(
        Arguments.of(
            new PriorAuthorityContent(
                "EXPERT",
                "Expert is required",
                new ExpertDetails("PSYCHIATRIST", "Jane Doe", "AB1 2CD", null),
                null,
                null),
            PriorAuthorityType.EXPERT,
            true,
            false,
            false),
        Arguments.of(
            new PriorAuthorityContent("EXPERT", "Expert is required", null, null, null),
            PriorAuthorityType.EXPERT,
            false,
            false,
            false),
        Arguments.of(
            new PriorAuthorityContent(
                "COUNSEL",
                "Counsel is required",
                null,
                new CounselDetails(CounselType.TWO_JUNIOR_COUNSEL),
                null),
            PriorAuthorityType.COUNSEL,
            false,
            true,
            false),
        Arguments.of(
            new PriorAuthorityContent("COUNSEL", "Counsel is required", null, null, null),
            PriorAuthorityType.COUNSEL,
            false,
            false,
            false),
        Arguments.of(
            new PriorAuthorityContent(
                "DISBURSEMENT",
                "Disbursement is required",
                null,
                null,
                new DisbursementDetails("Travel", BigDecimal.TEN)),
            PriorAuthorityType.DISBURSEMENT,
            false,
            false,
            true),
        Arguments.of(
            new PriorAuthorityContent("DISBURSEMENT", "Disbursement is required", null, null, null),
            PriorAuthorityType.DISBURSEMENT,
            false,
            false,
            false),
        Arguments.of(
            new PriorAuthorityContent(
                "",
                "Disbursement is required",
                null,
                null,
                new DisbursementDetails("Travel", BigDecimal.TEN)),
            null,
            false,
            false,
            false),
        Arguments.of(
            new PriorAuthorityContent(null, "", null, null, null), null, false, false, false));
  }

  @Test
  void givenExpertCostsWithNullableFields_whenRetrieved_thenHydratesAvailableValues() {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityReadModel readModel =
        PriorAuthorityReadModel.builder().submissionId(submissionId).dataVersion(1L).build();
    PriorAuthorityContent content =
        new PriorAuthorityContent(
            "EXPERT",
            "Expert is required",
            new ExpertDetails(
                "PSYCHIATRIST",
                "Jane Doe",
                "AB1 2CD",
                new ExpertCosts(BillingType.FIXED_RATE, null, null, BigDecimal.TEN, false, null)),
            null,
            null);
    when(queryGateway.query(
            any(FindPriorAuthorityBySubmissionIdQuery.class), eq(PriorAuthorityReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(readModel));
    when(dataStore.get(submissionId, 1L))
        .thenReturn(
            new PriorAuthorityDataPayload(submissionId, null, content, "{}", Instant.now()));

    PriorAuthorityResult response = useCase.getPriorAuthority(submissionId);

    assertThat(response.expertDetails().expertCosts().billingType().name()).isEqualTo("FIXED_RATE");
    assertThat(response.expertDetails().expertCosts().hourlyRate()).isNull();
    assertThat(response.expertDetails().expertCosts().timeRequested()).isNull();
    assertThat(response.expertDetails().expertCosts().totalAmount()).isEqualTo(BigDecimal.TEN);
    assertThat(response.expertDetails().expertCosts().apportionment()).isNull();
  }

  @Test
  void givenUnknownPriorAuthority_whenRetrieved_thenThrowsNotFound() {
    UUID submissionId = UUID.randomUUID();
    when(queryGateway.query(
            any(FindPriorAuthorityBySubmissionIdQuery.class), eq(PriorAuthorityReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> useCase.getPriorAuthority(submissionId))
        .withMessage("No prior authority found with ID: " + submissionId);
  }

  @Test
  void givenReadModelReferencesMissingPayloadVersion_whenRetrieved_thenThrowsConsistencyError() {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityReadModel readModel =
        PriorAuthorityReadModel.builder().submissionId(submissionId).dataVersion(2L).build();
    when(queryGateway.query(
            any(FindPriorAuthorityBySubmissionIdQuery.class), eq(PriorAuthorityReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(readModel));
    when(dataStore.get(submissionId, 2L))
        .thenThrow(
            new IllegalStateException(
                "Prior authority data not found for submission " + submissionId + " version 2"));

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> useCase.getPriorAuthority(submissionId))
        .withMessage(
            "Prior authority data not found for submission " + submissionId + " version 2");
  }
}
