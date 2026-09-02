package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.PriorAuthorityCreatedEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataStore;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.DisbursementDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.ExpertDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityType;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;

class PriorAuthorityProjectionTest {

  private PriorAuthorityReadRepository repository;
  private PriorAuthorityDataStore dataStore;
  private QueryUpdateEmitter queryUpdateEmitter;
  private PriorAuthorityProjection projection;

  @BeforeEach
  void setUp() {
    repository = mock(PriorAuthorityReadRepository.class);
    dataStore = mock(PriorAuthorityDataStore.class);
    queryUpdateEmitter = mock(QueryUpdateEmitter.class);
    projection = new PriorAuthorityProjection(repository, dataStore);
  }

  @Test
  @SuppressWarnings("unchecked")
  void givenCreatedEvent_whenHandled_thenSavesBeforeEmitting() {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityCreatedEvent event =
        new PriorAuthorityCreatedEvent(
            submissionId, UUID.randomUUID(), 1L, "fp", "SUBMITTED", 1, Instant.now());
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    projection.on(event, queryUpdateEmitter);

    InOrder order = inOrder(repository, queryUpdateEmitter);
    order.verify(repository).save(any(PriorAuthorityReadModel.class));
    QueryUpdateEmitter verifiedEmitter = order.verify(queryUpdateEmitter);
    verifiedEmitter.emit(any(Class.class), any(Predicate.class), any(Boolean.class));
  }

  @Test
  void givenCreatedEvent_whenHandled_thenSavesExactFields() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-19T10:00:00Z");
    PriorAuthorityCreatedEvent event =
        new PriorAuthorityCreatedEvent(
            submissionId, applicationId, 1L, "fp", "SUBMITTED", 1, occurredAt);
    PriorAuthorityReadModel[] savedCapture = new PriorAuthorityReadModel[1];
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              savedCapture[0] = invocation.getArgument(0);
              return savedCapture[0];
            });

    projection.on(event, queryUpdateEmitter);

    assertThat(savedCapture[0].getSubmissionId()).isEqualTo(submissionId);
    assertThat(savedCapture[0].getApplicationId()).isEqualTo(applicationId);
    assertThat(savedCapture[0].getDataVersion()).isEqualTo(1L);
    assertThat(savedCapture[0].getStatus()).isEqualTo("SUBMITTED");
    assertThat(savedCapture[0].getCreatedAt()).isEqualTo(occurredAt);
  }

  @Test
  @SuppressWarnings("unchecked")
  void givenCreatedEvent_whenHandled_thenEmittedPredicateMatchesOnlyEventSubmissionId() {
    UUID submissionId = UUID.randomUUID();
    final UUID otherId = UUID.randomUUID();
    PriorAuthorityCreatedEvent event =
        new PriorAuthorityCreatedEvent(
            submissionId, UUID.randomUUID(), 1L, "fp", "SUBMITTED", 1, Instant.now());
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Predicate<?>[] capturedPredicate = new Predicate[1];
    doAnswer(
            inv -> {
              capturedPredicate[0] = (Predicate<?>) inv.getArgument(1);
              return null;
            })
        .when(queryUpdateEmitter)
        .emit(any(Class.class), any(Predicate.class), any(Boolean.class));

    projection.on(event, queryUpdateEmitter);

    assertThat(capturedPredicate[0]).isNotNull();
    Predicate<PriorAuthorityExistsBySubmissionIdQuery> predicate =
        (Predicate<PriorAuthorityExistsBySubmissionIdQuery>) capturedPredicate[0];
    assertThat(predicate.test(new PriorAuthorityExistsBySubmissionIdQuery(submissionId))).isTrue();
    assertThat(predicate.test(new PriorAuthorityExistsBySubmissionIdQuery(otherId))).isFalse();
  }

  @Test
  void givenSubmissionId_whenQueryHandled_thenReturnsHydratedResult() {
    UUID submissionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityReadModel model =
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
    when(repository.findById(submissionId)).thenReturn(Optional.of(model));
    when(dataStore.get(submissionId, 4L))
        .thenReturn(
            new PriorAuthorityDataPayload(
                submissionId, applicationId, content, "{}", Instant.now()));

    PriorAuthorityResult result =
        projection.handle(new FindPriorAuthorityBySubmissionIdQuery(submissionId));

    assertThat(result.priorAuthorityId()).isEqualTo(submissionId);
    assertThat(result.applicationId()).isEqualTo(applicationId);
    assertThat(result.priorAuthorityType()).isEqualTo(PriorAuthorityType.COUNSEL);
    assertThat(result.justification()).isEqualTo("Counsel is required");
    assertThat(result.status()).isEqualTo("PENDING");
    assertThat(result.counselDetails().counselType()).isEqualTo(CounselType.TWO_JUNIOR_COUNSEL);
  }

  @Test
  void givenSupportedAndAbsentDetails_whenQueryHandled_thenHydratesOnlyMatchingDetails() {
    assertThat(
            handleContent(new PriorAuthorityContent("EXPERT", "Required", null, null, null))
                .expertDetails())
        .isNull();
    assertThat(
            handleContent(
                    new PriorAuthorityContent(
                        "EXPERT",
                        "Required",
                        new ExpertDetails("Accountant", "Ada Lovelace", "SW1A 1AA", null),
                        null,
                        null))
                .expertDetails()
                .expertFullName())
        .isEqualTo("Ada Lovelace");
    assertThat(
            handleContent(new PriorAuthorityContent("COUNSEL", "Required", null, null, null))
                .counselDetails())
        .isNull();
    assertThat(
            handleContent(
                    new PriorAuthorityContent(
                        "DISBURSEMENT",
                        "Required",
                        null,
                        null,
                        new DisbursementDetails("Travel", BigDecimal.TEN)))
                .disbursementDetails()
                .disbursementPurpose())
        .isEqualTo("Travel");
    assertThat(
            handleContent(new PriorAuthorityContent("DISBURSEMENT", "Required", null, null, null))
                .disbursementDetails())
        .isNull();
    assertThat(
            handleContent(new PriorAuthorityContent(null, "Required", null, null, null))
                .priorAuthorityType())
        .isNull();
    assertThat(
            handleContent(new PriorAuthorityContent("", "Required", null, null, null))
                .priorAuthorityType())
        .isNull();
  }

  @Test
  void givenMissingSubmissionId_whenQueryHandled_thenThrowsNotFound() {
    UUID submissionId = UUID.randomUUID();
    when(repository.findById(submissionId)).thenReturn(Optional.empty());

    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(
            () -> projection.handle(new FindPriorAuthorityBySubmissionIdQuery(submissionId)))
        .withMessage("No prior authority found with ID: " + submissionId);
  }

  @Test
  void givenExistingSubmissionId_whenExistsQueryHandled_thenReturnsTrue() {
    UUID submissionId = UUID.randomUUID();
    when(repository.existsById(submissionId)).thenReturn(true);

    Optional<Boolean> result =
        projection.handle(new PriorAuthorityExistsBySubmissionIdQuery(submissionId));

    assertThat(result).contains(Boolean.TRUE);
  }

  @Test
  void givenMissingSubmissionId_whenExistsQueryHandled_thenReturnsEmpty() {
    UUID submissionId = UUID.randomUUID();
    when(repository.existsById(submissionId)).thenReturn(false);

    Optional<Boolean> result =
        projection.handle(new PriorAuthorityExistsBySubmissionIdQuery(submissionId));

    assertThat(result).isEmpty();
  }

  @Test
  void givenResetCalled_whenHandled_thenDeletesAllInBatch() {
    projection.reset();

    verify(repository).deleteAllInBatch();
  }

  private PriorAuthorityResult handleContent(PriorAuthorityContent content) {
    UUID submissionId = UUID.randomUUID();
    PriorAuthorityReadModel model =
        PriorAuthorityReadModel.builder()
            .submissionId(submissionId)
            .applicationId(UUID.randomUUID())
            .dataVersion(1L)
            .status("PENDING")
            .build();
    when(repository.findById(submissionId)).thenReturn(Optional.of(model));
    when(dataStore.get(submissionId, 1L))
        .thenReturn(
            new PriorAuthorityDataPayload(
                submissionId, model.getApplicationId(), content, "{}", Instant.now()));

    return projection.handle(new FindPriorAuthorityBySubmissionIdQuery(submissionId));
  }
}
