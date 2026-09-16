package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityType.*;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.DisbursementInformation;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.PriorAuthorityDraftStartedEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.PriorAuthoritySubmittedEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataStore;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDraftStore;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.decision.PriorAuthorityDecisionMadeEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.document.UploadedDocumentStore;
import uk.gov.justice.laa.dstew.access.content.priorauthority.Apportionment;
import uk.gov.justice.laa.dstew.access.content.priorauthority.BillingType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.DisbursementDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertCosts;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.TimeRequested;

@ExtendWith(MockitoExtension.class)
class PriorAuthorityProjectionTest {

  @Mock private PriorAuthorityReadRepository repository;
  @Mock private PriorAuthorityDataStore dataStore;
  @Mock private PriorAuthorityDraftStore draftStore;
  @Mock private UploadedDocumentStore uploadedDocumentStore;
  @Mock private QueryUpdateEmitter queryUpdateEmitter;
  @InjectMocks private PriorAuthorityProjection projection;

  @Test
  void givenSubmittedEvent_whenHandled_thenSavesExactFields() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");
    PriorAuthoritySubmittedEvent event =
        new PriorAuthoritySubmittedEvent(
            priorAuthorityId, applicationId, "EXPERT", 1, 1L, 0L, occurredAt);
    PriorAuthorityReadModel[] savedCapture = new PriorAuthorityReadModel[1];
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              savedCapture[0] = invocation.getArgument(0);
              return savedCapture[0];
            });

    projection.on(event, queryUpdateEmitter);

    assertThat(savedCapture[0].getPriorAuthorityId()).isEqualTo(priorAuthorityId);
    assertThat(savedCapture[0].getApplicationId()).isEqualTo(applicationId);
    assertThat(savedCapture[0].getDataVersion()).isEqualTo(1L);
    assertThat(savedCapture[0].getStatus()).isEqualTo("SUBMITTED");
    assertThat(savedCapture[0].getCreatedAt()).isEqualTo(occurredAt);
  }

  @Test
  void givenDraftStartedEvent_whenHandled_thenCreatesCurrentStateRow() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-04T10:00:00Z");
    PriorAuthorityDraftStartedEvent event =
        new PriorAuthorityDraftStartedEvent(
            priorAuthorityId, applicationId, EXPERT.name(), 1, occurredAt);
    PriorAuthorityReadModel[] savedCapture = new PriorAuthorityReadModel[1];
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              savedCapture[0] = invocation.getArgument(0);
              return savedCapture[0];
            });

    projection.on(event, queryUpdateEmitter);

    assertThat(savedCapture[0].getPriorAuthorityId()).isEqualTo(priorAuthorityId);
    assertThat(savedCapture[0].getApplicationId()).isEqualTo(applicationId);
    assertThat(savedCapture[0].getDataVersion()).isZero();
    assertThat(savedCapture[0].getStatus()).isEqualTo("DRAFT");
    assertThat(savedCapture[0].getCreatedAt()).isEqualTo(occurredAt);
  }

  @Test
  void givenDraftRowWithDraftStatus_whenQueryHandled_thenHydratesDraftContent() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(applicationId)
            .dataVersion(0L)
            .status("DRAFT")
            .build();
    PriorAuthorityContent content =
        new PriorAuthorityContent(EXPERT, "Expert required", null, null, null);
    when(repository.findById(priorAuthorityId)).thenReturn(Optional.of(model));
    when(draftStore.find(priorAuthorityId))
        .thenReturn(
            Optional.of(
                new PriorAuthorityDataPayload(
                    priorAuthorityId, applicationId, content, "{}", Instant.now())));

    PriorAuthorityResult result =
        projection.handle(new FindPriorAuthorityByPriorAuthorityIdQuery(priorAuthorityId));

    assertThat(result.priorAuthorityId()).isEqualTo(priorAuthorityId);
    assertThat(result.applicationId()).isEqualTo(applicationId);
    assertThat(result.status()).isEqualTo("DRAFT");
    assertThat(result.priorAuthorityType()).isEqualTo(EXPERT);
  }

  @Test
  void givenPriorAuthorityId_whenQueryHandled_thenReturnsHydratedResult() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(applicationId)
            .dataVersion(4L)
            .status("SUBMITTED")
            .build();
    PriorAuthorityContent content =
        new PriorAuthorityContent(
            COUNSEL,
            "Counsel is required",
            null,
            new CounselDetails(CounselType.TWO_JUNIOR_COUNSEL),
            null);
    when(repository.findById(priorAuthorityId)).thenReturn(Optional.of(model));
    when(dataStore.get(priorAuthorityId, 4L))
        .thenReturn(
            new PriorAuthorityDataPayload(
                priorAuthorityId, applicationId, content, "{}", Instant.now()));

    PriorAuthorityResult result =
        projection.handle(new FindPriorAuthorityByPriorAuthorityIdQuery(priorAuthorityId));

    assertThat(result.priorAuthorityId()).isEqualTo(priorAuthorityId);
    assertThat(result.applicationId()).isEqualTo(applicationId);
    assertThat(result.priorAuthorityType()).isEqualTo(COUNSEL);
    assertThat(result.justification()).isEqualTo("Counsel is required");
    assertThat(result.status()).isEqualTo("SUBMITTED");
    assertThat(result.counselDetails().counselType()).isEqualTo(CounselType.TWO_JUNIOR_COUNSEL);
    assertThat(result.expertDetails()).isNull();
    assertThat(result.disbursementDetails()).isNull();
  }

  @Test
  void givenExpertDetails_whenQueryHandled_thenHydratesOnlyExpertDetails() {
    ExpertCosts expertCosts =
        new ExpertCosts(
            BillingType.HOURLY,
            new BigDecimal("125.50"),
            new TimeRequested(2, 30),
            new BigDecimal("313.75"),
            true,
            new Apportionment(3, new BigDecimal("104.58")));
    PriorAuthorityResult expertResult =
        handleContent(
            new PriorAuthorityContent(
                EXPERT,
                "Expert required",
                new ExpertDetails("Accountant", "Ada Lovelace", "SW1A 1AA", expertCosts),
                null,
                null));

    assertThat(expertResult.priorAuthorityType()).isEqualTo(EXPERT);
    assertThat(expertResult.justification()).isEqualTo("Expert required");
    assertThat(expertResult.expertDetails().expertType()).isEqualTo("Accountant");
    assertThat(expertResult.expertDetails().expertFullName()).isEqualTo("Ada Lovelace");
    assertThat(expertResult.expertDetails().expertPostcode()).isEqualTo("SW1A 1AA");
    assertThat(expertResult.expertDetails().expertCosts().billingType())
        .isEqualTo(BillingType.HOURLY);
    assertThat(expertResult.expertDetails().expertCosts().hourlyRate())
        .isEqualTo(new BigDecimal("125.50"));
    assertThat(expertResult.expertDetails().expertCosts().timeRequested().hours()).isEqualTo(2);
    assertThat(expertResult.expertDetails().expertCosts().timeRequested().minutes()).isEqualTo(30);
    assertThat(expertResult.expertDetails().expertCosts().totalAmount())
        .isEqualTo(new BigDecimal("313.75"));
    assertThat(expertResult.expertDetails().expertCosts().costsSharedWithOtherParties()).isTrue();
    assertThat(expertResult.expertDetails().expertCosts().apportionment().partiesSharingCosts())
        .isEqualTo(3);
    assertThat(expertResult.expertDetails().expertCosts().apportionment().clientShareAmount())
        .isEqualTo(new BigDecimal("104.58"));
    assertThat(expertResult.counselDetails()).isNull();
    assertThat(expertResult.disbursementDetails()).isNull();
  }

  @Test
  void givenCounselDetails_whenQueryHandled_thenHydratesOnlyCounselDetails() {
    PriorAuthorityResult counselResult =
        handleContent(
            new PriorAuthorityContent(
                COUNSEL,
                "Counsel required",
                null,
                new CounselDetails(CounselType.TWO_JUNIOR_COUNSEL),
                null));

    assertThat(counselResult.priorAuthorityType()).isEqualTo(COUNSEL);
    assertThat(counselResult.justification()).isEqualTo("Counsel required");
    assertThat(counselResult.counselDetails().counselType())
        .isEqualTo(CounselType.TWO_JUNIOR_COUNSEL);
    assertThat(counselResult.expertDetails()).isNull();
    assertThat(counselResult.disbursementDetails()).isNull();
  }

  @Test
  void givenDisbursementDetails_whenQueryHandled_thenHydratesOnlyDisbursementDetails() {
    PriorAuthorityResult disbursementResult =
        handleContent(
            new PriorAuthorityContent(
                DISBURSEMENT,
                "Disbursement required",
                null,
                null,
                new DisbursementDetails("Travel", BigDecimal.TEN)));

    assertThat(disbursementResult.priorAuthorityType()).isEqualTo(DISBURSEMENT);
    assertThat(disbursementResult.justification()).isEqualTo("Disbursement required");
    assertThat(disbursementResult.disbursementDetails().disbursementPurpose()).isEqualTo("Travel");
    assertThat(disbursementResult.disbursementDetails().disbursementAmount())
        .isEqualTo(BigDecimal.TEN);
    assertThat(disbursementResult.expertDetails()).isNull();
    assertThat(disbursementResult.counselDetails()).isNull();
  }

  @ParameterizedTest
  @EnumSource(PriorAuthorityType.class)
  void givenTypeWithAbsentDetails_whenQueryHandled_thenHydratedDetailsAreNull(
      PriorAuthorityType type) {
    PriorAuthorityResult result =
        handleContent(new PriorAuthorityContent(type, "Required", null, null, null));

    assertThat(result.expertDetails()).isNull();
    assertThat(result.counselDetails()).isNull();
    assertThat(result.disbursementDetails()).isNull();
  }

  @Test
  void givenNullType_whenQueryHandled_thenPriorAuthorityTypeIsNull() {
    PriorAuthorityResult result =
        handleContent(new PriorAuthorityContent(null, "Required", null, null, null));

    assertThat(result.priorAuthorityType()).isNull();
  }

  @Test
  void givenMissingPriorAuthorityId_whenQueryHandled_thenReturnsNull() {
    UUID priorAuthorityId = UUID.randomUUID();
    when(repository.findById(priorAuthorityId)).thenReturn(Optional.empty());

    assertThat(projection.handle(new FindPriorAuthorityByPriorAuthorityIdQuery(priorAuthorityId)))
        .isNull();
  }

  @Test
  void givenDecisionMadeEvent_whenHandled_thenUpdatesCurrentStateVersion() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(applicationId)
            .dataVersion(0L)
            .status("SUBMITTED")
            .build();
    when(repository.findById(priorAuthorityId)).thenReturn(Optional.of(model));

    projection.on(
        new PriorAuthorityDecisionMadeEvent(
            priorAuthorityId,
            applicationId,
            "EXPERT",
            1L,
            "REFUSED",
            "Refused on merits",
            null,
            null,
            Instant.now()));

    assertThat(model.getStatus()).isEqualTo("DECIDED");
    assertThat(model.getDataVersion()).isEqualTo(1L);
    verify(repository).save(model);
  }

  @Test
  void givenDecisionStoredInPayload_whenQueryHandled_thenReturnsDecisionDetails() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(applicationId)
            .dataVersion(2L)
            .status("SUBMITTED")
            .build();
    PriorAuthorityContent content =
        new PriorAuthorityContent(EXPERT, "Expert required", null, null, null);
    when(repository.findById(priorAuthorityId)).thenReturn(Optional.of(model));
    when(dataStore.get(priorAuthorityId, 2L))
        .thenReturn(
            new PriorAuthorityDataPayload(
                priorAuthorityId,
                applicationId,
                content,
                "{}",
                Instant.now(),
                new PriorAuthorityDataPayload.DecisionDetails(
                    "GRANTED",
                    "Decision recorded",
                    BigDecimal.valueOf(99.99),
                    Instant.parse("2026-09-08T12:00:00Z"),
                    null,
                    DisbursementInformation.builder().build(),
                    null,
                    "{\"decision\":\"GRANTED\"}")));

    PriorAuthorityResult result =
        projection.handle(new FindPriorAuthorityByPriorAuthorityIdQuery(priorAuthorityId));

    assertThat(result.status()).isEqualTo("SUBMITTED");
    assertThat(result.decisionDetails()).isNotNull();
    assertThat(result.decisionDetails().decision()).isEqualTo("GRANTED");
  }

  @Test
  void givenDecidedPayload_whenPendingQueryHandled_thenReturnsFalse() {
    UUID priorAuthorityId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(UUID.randomUUID())
            .dataVersion(1L)
            .status("DECIDED")
            .build();
    when(repository.findById(priorAuthorityId)).thenReturn(Optional.of(model));

    assertThat(
            projection.handle(new PriorAuthorityPendingByPriorAuthorityIdQuery(priorAuthorityId)))
        .isFalse();
  }

  @Test
  void givenSubmittedPayloadWithoutDecision_whenPendingQueryHandled_thenReturnsTrue() {
    UUID priorAuthorityId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(UUID.randomUUID())
            .dataVersion(1L)
            .status("SUBMITTED")
            .build();
    when(repository.findById(priorAuthorityId)).thenReturn(Optional.of(model));

    assertThat(
            projection.handle(new PriorAuthorityPendingByPriorAuthorityIdQuery(priorAuthorityId)))
        .isTrue();
  }

  @Test
  void givenDraftStatus_whenPrivateStatusOfEvaluated_thenReturnsDraft() throws Exception {
    UUID priorAuthorityId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(UUID.randomUUID())
            .dataVersion(0L)
            .status("DRAFT")
            .build();
    Method method =
        PriorAuthorityProjection.class.getDeclaredMethod(
            "isPending", PriorAuthorityReadModel.class);
    method.setAccessible(true);

    assertThat((boolean) method.invoke(projection, model)).isFalse();
  }

  @Test
  void givenResetCalled_whenHandled_thenDeletesAllInBatch() {
    projection.reset();

    verify(repository).deleteAllInBatch();
  }

  private PriorAuthorityResult handleContent(PriorAuthorityContent content) {
    UUID priorAuthorityId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(UUID.randomUUID())
            .dataVersion(1L)
            .status("SUBMITTED")
            .build();
    when(repository.findById(priorAuthorityId)).thenReturn(Optional.of(model));
    when(dataStore.get(priorAuthorityId, 1L))
        .thenReturn(
            new PriorAuthorityDataPayload(
                priorAuthorityId, model.getApplicationId(), content, "{}", Instant.now()));

    return projection.handle(new FindPriorAuthorityByPriorAuthorityIdQuery(priorAuthorityId));
  }
}
