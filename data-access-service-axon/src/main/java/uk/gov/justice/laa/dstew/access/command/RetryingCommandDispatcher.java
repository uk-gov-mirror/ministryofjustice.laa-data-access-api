package uk.gov.justice.laa.dstew.access.command;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.modelling.ConcurrencyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Wraps Axon's {@link CommandGateway} with a single retry on concurrent-write failures.
 *
 * <p>Retries once when a {@link ConcurrencyException}, {@link
 * AppendEventsTransactionRejectedException}, or a unique-constraint {@link
 * DataIntegrityViolationException} (SQL state {@code 23505}) is thrown. All other exceptions are
 * propagated immediately.
 */
@Component
public class RetryingCommandDispatcher {

  private static final String UNIQUE_CONSTRAINT_VIOLATION = "23505";

  private final CommandGateway commandGateway;

  public RetryingCommandDispatcher(CommandGateway commandGateway) {
    this.commandGateway = commandGateway;
  }

  /** Dispatches the command, retrying once on concurrent-write failures. */
  public void dispatch(Object command) {
    dispatch(() -> commandGateway.sendAndWait(command));
  }

  /**
   * Dispatches the command and returns its response, retrying once on concurrent-write failures.
   */
  public <R> R dispatch(Object command, Class<R> responseType) {
    return dispatch(() -> commandGateway.sendAndWait(command, responseType));
  }

  private <R> R dispatch(Supplier<R> dispatch) {
    try {
      return dispatch.get();
    } catch (RuntimeException exception) {
      RuntimeException first = unwrapCommandExecutionException(exception);
      if (!isRetryableConcurrentWrite(first)) {
        throw first;
      }
      try {
        return dispatch.get();
      } catch (RuntimeException exceptionOnRetry) {
        RuntimeException retry = unwrapCommandExecutionException(exceptionOnRetry);
        retry.addSuppressed(first);
        throw retry;
      }
    }
  }

  private boolean isRetryableConcurrentWrite(RuntimeException exception) {
    return exception instanceof ConcurrencyException
        || exception instanceof AppendEventsTransactionRejectedException
        || exception instanceof DataIntegrityViolationException
            && hasUniqueConstraintViolation(exception);
  }

  /**
   * Extracts the runtime failure raised by an Axon command handler from Axon's dispatch wrapper.
   *
   * <p>Axon reports exceptions thrown while handling a command as {@link
   * CommandExecutionException}. The retry policy must inspect the handler's underlying failure,
   * such as a {@link ConcurrencyException} or {@link DataIntegrityViolationException}, rather than
   * the wrapper; otherwise retryable concurrent-write failures would be treated as non-retryable.
   * Non-runtime causes remain wrapped because the dispatcher only propagates and classifies runtime
   * failures.
   */
  private RuntimeException unwrapCommandExecutionException(RuntimeException exception) {
    if (exception instanceof CommandExecutionException
        && exception.getCause() instanceof RuntimeException cause) {
      return cause;
    }
    return exception;
  }

  private boolean hasUniqueConstraintViolation(Throwable exception) {
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof SQLException sqlException
          && UNIQUE_CONSTRAINT_VIOLATION.equals(sqlException.getSQLState())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }
}
