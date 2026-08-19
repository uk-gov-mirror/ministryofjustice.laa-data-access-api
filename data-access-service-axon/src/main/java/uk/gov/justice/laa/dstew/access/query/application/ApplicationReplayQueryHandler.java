package uk.gov.justice.laa.dstew.access.query.application;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Handles {@link FindApplicationByIdQuery} by delegating to {@link
 * ApplicationEventStoreReplayService}, which reconstructs the Application's current-state read
 * model directly from the raw Axon event store, bypassing the running projection entirely.
 */
@Component
public class ApplicationReplayQueryHandler {

  private final ApplicationEventStoreReplayService eventStoreReplayService;

  /** Constructs the handler with its raw-replay service. */
  public ApplicationReplayQueryHandler(ApplicationEventStoreReplayService eventStoreReplayService) {
    this.eventStoreReplayService = eventStoreReplayService;
  }

  @QueryHandler
  public @Nullable ApplicationReadModel handle(FindApplicationByIdQuery query) {
    return eventStoreReplayService.replay(query.applicationId()).orElse(null);
  }
}
