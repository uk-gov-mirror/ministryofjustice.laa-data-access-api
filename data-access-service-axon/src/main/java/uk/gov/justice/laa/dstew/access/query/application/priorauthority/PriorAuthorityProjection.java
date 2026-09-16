package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.replay.annotation.ResetHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.PriorAuthorityDocumentDeletedEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.PriorAuthorityDocumentUploadedEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.PriorAuthorityDraftStartedEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.PriorAuthoritySubmittedEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataStore;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDraftStore;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.decision.PriorAuthorityDecisionMadeEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.document.UploadedDocumentStore;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityDocument;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityStatus;

/** Independently replayable projection of the current state of each prior-authority submission. */
@Component
@Namespace("prior-authority-projection")
public class PriorAuthorityProjection {

  private final PriorAuthorityReadRepository repository;
  private final PriorAuthorityDataStore priorAuthorityDataStore;
  private final PriorAuthorityDraftStore priorAuthorityDraftStore;
  private final UploadedDocumentStore uploadedDocumentStore;

  /**
   * Creates the prior-authority current-state projection.
   *
   * @param repository persistence for the projected current state
   * @param priorAuthorityDataStore storage for submitted prior-authority content
   * @param priorAuthorityDraftStore storage for in-progress draft content
   * @param uploadedDocumentStore storage for uploaded document metadata
   */
  public PriorAuthorityProjection(
      PriorAuthorityReadRepository repository,
      PriorAuthorityDataStore priorAuthorityDataStore,
      PriorAuthorityDraftStore priorAuthorityDraftStore,
      UploadedDocumentStore uploadedDocumentStore) {
    this.repository = repository;
    this.priorAuthorityDataStore = priorAuthorityDataStore;
    this.priorAuthorityDraftStore = priorAuthorityDraftStore;
    this.uploadedDocumentStore = uploadedDocumentStore;
  }

  /** Returns the hydrated current state for the requested prior-authority submission. */
  @QueryHandler
  public PriorAuthorityResult handle(FindPriorAuthorityByPriorAuthorityIdQuery query) {
    UUID priorAuthorityId = query.priorAuthorityId();
    return repository
        .findById(priorAuthorityId)
        .flatMap(result -> hydrate(result, priorAuthorityId))
        .orElse(null);
  }

  /** Confirms whether a current-state projection has reached SUBMITTED. */
  @QueryHandler
  public boolean handle(PriorAuthorityPendingByPriorAuthorityIdQuery query) {
    return repository.findById(query.priorAuthorityId()).map(this::isPending).orElse(false);
  }

  private Optional<@NonNull PriorAuthorityResult> hydrate(
      PriorAuthorityReadModel priorAuthority, UUID priorAuthorityId) {
    if (PriorAuthorityStatus.DRAFT.name().equals(priorAuthority.getStatus())) {
      return priorAuthorityDraftStore
          .find(priorAuthorityId)
          .map(PriorAuthorityResult::fromDraft)
          .map(result -> result.withUploadedDocuments(documentsFor(priorAuthority)));
    }
    PriorAuthorityDataPayload payload =
        priorAuthorityDataStore.get(priorAuthorityId, priorAuthority.getDataVersion());
    return Optional.of(
        PriorAuthorityResult.from(priorAuthority, payload, priorAuthority.getStatus())
            .withUploadedDocuments(documentsFor(priorAuthority)));
  }

  private List<PriorAuthorityDocument> documentsFor(PriorAuthorityReadModel priorAuthority) {
    List<UUID> documentIds = priorAuthority.getUploadedDocumentIds();
    if (documentIds == null || documentIds.isEmpty()) {
      return List.of();
    }
    return uploadedDocumentStore.findAllInOrder(documentIds);
  }

  /** Creates the current-state row when a prior-authority draft is started. */
  @EventHandler
  public void on(PriorAuthorityDraftStartedEvent event, QueryUpdateEmitter queryUpdateEmitter) {
    createRow(
        event.priorAuthorityId(),
        event.applicationId(),
        0L,
        PriorAuthorityStatus.DRAFT.name(),
        event.occurredAt(),
        queryUpdateEmitter);
  }

  /** Creates the current-state row once a prior-authority draft has been submitted. */
  @EventHandler
  public void on(PriorAuthoritySubmittedEvent event, QueryUpdateEmitter queryUpdateEmitter) {
    createRow(
        event.priorAuthorityId(),
        event.applicationId(),
        event.dataVersion(),
        PriorAuthorityStatus.SUBMITTED.name(),
        event.occurredAt(),
        queryUpdateEmitter);
  }

  /** Updates current-state data version after a terminal prior-authority decision. */
  @EventHandler
  public void on(PriorAuthorityDecisionMadeEvent event) {
    repository
        .findById(event.priorAuthorityId())
        .ifPresent(
            current -> {
              current.setDataVersion(event.dataVersion());
              current.setStatus(PriorAuthorityStatus.DECIDED.name());
              current.setModifiedAt(event.occurredAt());
              repository.save(current);
            });
  }

  /** Records the aggregate's document ID list in the replayable current-state projection. */
  @EventHandler
  public void on(PriorAuthorityDocumentUploadedEvent event) {
    repository
        .findById(event.priorAuthorityId())
        .ifPresent(
            priorAuthority -> {
              List<UUID> documentIds = new ArrayList<>(documentIdsOf(priorAuthority));
              documentIds.add(event.documentId());
              priorAuthority.setUploadedDocumentIds(List.copyOf(documentIds));
              repository.save(priorAuthority);
            });
  }

  /** Removes a deleted document id from the replayable current-state projection. */
  @EventHandler
  public void on(PriorAuthorityDocumentDeletedEvent event) {
    repository
        .findById(event.priorAuthorityId())
        .ifPresent(
            priorAuthority -> {
              List<UUID> documentIds = new ArrayList<>(documentIdsOf(priorAuthority));
              documentIds.remove(event.documentId());
              priorAuthority.setUploadedDocumentIds(List.copyOf(documentIds));
              repository.save(priorAuthority);
            });
  }

  private boolean isPending(PriorAuthorityReadModel priorAuthority) {
    return PriorAuthorityStatus.SUBMITTED.name().equals(priorAuthority.getStatus());
  }

  private static List<UUID> documentIdsOf(PriorAuthorityReadModel priorAuthority) {
    return priorAuthority.getUploadedDocumentIds() == null
        ? List.of()
        : priorAuthority.getUploadedDocumentIds();
  }

  private void createRow(
      UUID priorAuthorityId,
      UUID applicationId,
      long dataVersion,
      String status,
      Instant occurredAt,
      QueryUpdateEmitter queryUpdateEmitter) {
    repository.save(
        PriorAuthorityReadModel.builder()
            .priorAuthorityId(priorAuthorityId)
            .applicationId(applicationId)
            .dataVersion(dataVersion)
            .status(status)
            .createdAt(occurredAt)
            .modifiedAt(occurredAt)
            .uploadedDocumentIds(List.of())
            .build());
    if (PriorAuthorityStatus.SUBMITTED.name().equals(status)) {
      queryUpdateEmitter.emit(
          PriorAuthorityPendingByPriorAuthorityIdQuery.class,
          query -> query.priorAuthorityId().equals(priorAuthorityId),
          Boolean.TRUE);
    }
  }

  /** Clears the disposable current-state table before replay. */
  @ResetHandler
  public void reset() {
    repository.deleteAllInBatch();
  }
}
