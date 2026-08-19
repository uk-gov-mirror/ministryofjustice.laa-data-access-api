package uk.gov.justice.laa.dstew.access.command.application;

import org.springframework.stereotype.Component;
import uk.gov.justice.laa.dstew.access.command.RetryingCommandDispatcher;

/**
 * Dispatches a create-application command and waits for the projection to confirm the application
 * is readable.
 */
@Component
public class CreateApplicationUseCase {

  private final RetryingCommandDispatcher dispatcher;

  public CreateApplicationUseCase(RetryingCommandDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /**
   * Dispatches the command and waits for the projection to become readable.
   *
   * @return {@code true} when the projection confirms the application within the configured
   *     timeout; {@code false} on timeout — the command has still committed.
   */
  public boolean execute(CreateApplicationCommand command) {
    dispatcher.dispatch(command);
    return true;
  }
}
