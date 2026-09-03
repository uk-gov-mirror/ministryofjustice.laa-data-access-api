package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDraftStore;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.DisbursementDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertCosts;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;
import uk.gov.justice.laa.dstew.access.query.application.priorauthority.model.CounselType;
import uk.gov.justice.laa.dstew.access.query.application.priorauthority.model.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.query.application.priorauthority.model.PriorAuthorityType;

class GetPriorAuthorityUseCaseTest {

  private PriorAuthorityDataStore dataStore;
  private PriorAuthorityDraftStore draftStore;
  private QueryGateway queryGateway;
  private GetPriorAuthorityUseCase useCase;

  @BeforeEach
  void setUp() {
    dataStore = org.mockito.Mockito.mock(PriorAuthorityDataStore.class);
    draftStore = org.mockito.Mockito.mock(PriorAuthorityDraftStore.class);
    queryGateway = org.mockito.Mockito.mock(QueryGateway.class);
    useCase = new GetPriorAuthorityUseCase(dataStore, draftStore, queryGateway);
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
            new CounselDetails("TWO_JUNIOR_COUNSEL"),
            null,
            List.of());
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
                null,
                List.of()),
            PriorAuthorityType.EXPERT,
            true,
            false,
            false),
        Arguments.of(
            new PriorAuthorityContent("EXPERT", "Expert is required", null, null, null, List.of()),
            PriorAuthorityType.EXPERT,
            false,
            false,
            false),
        Arguments.of(
            new PriorAuthorityContent(
                "COUNSEL",
                "Counsel is required",
                null,
                new CounselDetails("TWO_JUNIOR_COUNSEL"),
                null,
                List.of()),
            PriorAuthorityType.COUNSEL,
            false,
            true,
            false),
        Arguments.of(
            new PriorAuthorityContent(
                "COUNSEL", "Counsel is required", null, null, null, List.of()),
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
                new DisbursementDetails("Travel", BigDecimal.TEN),
                List.of()),
            PriorAuthorityType.DISBURSEMENT,
            false,
            false,
            true),
        Arguments.of(
            new PriorAuthorityContent(
                "DISBURSEMENT", "Disbursement is required", null, null, null, List.of()),
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
                new DisbursementDetails("Travel", BigDecimal.TEN),
                List.of()),
            null,
            false,
            false,
            false),
        Arguments.of(
            new PriorAuthorityContent(null, "", null, null, null, List.of()),
            null,
            false,
            false,
            false));
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
                new ExpertCosts("FIXED_RATE", null, null, BigDecimal.TEN, false, null)),
            null,
            null,
            List.of());
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
    when(draftStore.find(submissionId)).thenReturn(Optional.empty());

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> useCase.getPriorAuthority(submissionId))
        .withMessage("No prior authority found with ID: " + submissionId);
  }

  @Test
  void givenInProgressDraft_whenRetrieved_thenFallsBackToDraftStoreWithNullStatus() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityContent content =
        new PriorAuthorityContent(
            "EXPERT",
            "Expert is required",
            new ExpertDetails("PSYCHIATRIST", "Jane Doe", "AB1 2CD", null),
            null,
            null,
            List.of());
    when(queryGateway.query(
            any(FindPriorAuthorityBySubmissionIdQuery.class), eq(PriorAuthorityReadModel.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(draftStore.find(submissionId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    submissionId,
                    applicationId,
                    content,
                    "{}",
                    Instant.parse("2026-08-26T10:00:00Z"))));

    PriorAuthorityResult result = useCase.getPriorAuthority(submissionId);

    assertThat(result.priorAuthorityId()).isEqualTo(submissionId);
    assertThat(result.applicationId()).isEqualTo(applicationId);
    assertThat(result.status()).isNull();
    assertThat(result.priorAuthorityType()).isEqualTo(PriorAuthorityType.EXPERT);
    assertThat(result.expertDetails()).isNotNull();
    assertThat(result.expertDetails().expertFullName()).isEqualTo("Jane Doe");
    verify(dataStore, org.mockito.Mockito.never()).get(any(), anyLong());
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
