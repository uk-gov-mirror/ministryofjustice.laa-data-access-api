package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import java.util.Optional;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.replay.annotation.ResetHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.PriorAuthorityCreatedEvent;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataPayload;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.data.PriorAuthorityDataStore;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityResult;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;

/** Independently replayable projection of the current state of each prior-authority submission. */
@Component
@Namespace("prior-authority-projection")
public class PriorAuthorityProjection {

  private final PriorAuthorityReadRepository repository;
  private final PriorAuthorityDataStore priorAuthorityDataStore;

  public PriorAuthorityProjection(
      PriorAuthorityReadRepository repository, PriorAuthorityDataStore priorAuthorityDataStore) {
    this.repository = repository;
    this.priorAuthorityDataStore = priorAuthorityDataStore;
  }

  /** Returns the hydrated current state for the requested prior-authority submission. */
  @QueryHandler
  public PriorAuthorityResult handle(FindPriorAuthorityBySubmissionIdQuery query) {
    PriorAuthorityReadModel priorAuthority =
        repository
            .findById(query.submissionId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "No prior authority found with ID: " + query.submissionId()));
    PriorAuthorityDataPayload payload =
        priorAuthorityDataStore.get(query.submissionId(), priorAuthority.getDataVersion());
    return PriorAuthorityResult.from(priorAuthority, payload.content());
  }

  /** Confirms whether a current-state projection exists for the requested submission. */
  @QueryHandler
  public Optional<Boolean> handle(PriorAuthorityExistsBySubmissionIdQuery query) {
    return repository.existsById(query.submissionId())
        ? Optional.of(Boolean.TRUE)
        : Optional.empty();
  }

  /** Creates the current-state row from a prior-authority creation event. */
  @EventHandler
  public void on(PriorAuthorityCreatedEvent event, QueryUpdateEmitter queryUpdateEmitter) {
    repository.save(
        PriorAuthorityReadModel.builder()
            .submissionId(event.submissionId())
            .applicationId(event.applicationId())
            .dataVersion(event.dataVersion())
            .status(event.status())
            .createdAt(event.occurredAt())
            .build());
    queryUpdateEmitter.emit(
        PriorAuthorityExistsBySubmissionIdQuery.class,
        query -> query.submissionId().equals(event.submissionId()),
        Boolean.TRUE);
  }

  /** Clears the disposable current-state table before replay. */
  @ResetHandler
  public void reset() {
    repository.deleteAllInBatch();
  }
}
