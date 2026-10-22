package uk.gov.justice.laa.dstew.access.command.application.ready;

import org.springframework.stereotype.Component;
import uk.gov.justice.laa.dstew.access.command.RetryingCommandDispatcher;

/**
 * Dispatches outcomes after starter-managed HTTP authorization, retrying concurrent writes once.
 */
@Component
public class RecordAutoGrantOutcomeUseCase {

  private final RetryingCommandDispatcher dispatcher;

  public RecordAutoGrantOutcomeUseCase(RetryingCommandDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /** Dispatches an outcome that marks the application ready for manual assessment. */
  public ReadyApplicationResult recordReady(MarkApplicationReadyCommand command) {
    return dispatcher.dispatch(command, ReadyApplicationResult.class);
  }

  /** Dispatches any other terminal auto-grant outcome command. */
  public void record(Object command) {
    dispatcher.dispatch(command);
  }
}
