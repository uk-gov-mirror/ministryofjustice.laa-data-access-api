package uk.gov.justice.laa.dstew.access.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.modelling.ConcurrencyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.justice.laa.dstew.access.exception.ApplicationVersionConflictException;
import uk.gov.justice.laa.dstew.access.exception.ResourceNotFoundException;

class RetryingCommandDispatcherTest {

  private CommandGateway commandGateway;
  private RetryingCommandDispatcher dispatcher;
  private final Object command = new Object();

  @BeforeEach
  void setUp() {
    commandGateway = mock(CommandGateway.class);
    dispatcher = new RetryingCommandDispatcher(commandGateway);
  }

  @Test
  void givenSuccessfulDispatch_whenDispatch_thenSendsOnce() {
    dispatcher.dispatch(command);

    verify(commandGateway, times(1)).sendAndWait(command);
  }

  @Test
  void givenConcurrencyException_whenDispatch_thenRetriesOnce() {
    when(commandGateway.sendAndWait(command))
        .thenThrow(new ConcurrencyException("concurrent write"))
        .thenReturn(null);

    dispatcher.dispatch(command);

    verify(commandGateway, times(2)).sendAndWait(command);
  }

  @Test
  void givenWrappedConcurrencyException_whenDispatch_thenRetriesOnce() {
    when(commandGateway.sendAndWait(command))
        .thenThrow(
            new CommandExecutionException(
                "command failed", new ConcurrencyException("concurrent write")))
        .thenReturn(null);

    dispatcher.dispatch(command);

    verify(commandGateway, times(2)).sendAndWait(command);
  }

  @Test
  void givenUniqueConstraintViolation_whenDispatch_thenRetriesOnce() {
    when(commandGateway.sendAndWait(command))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key", new SQLException("duplicate key value", "23505")))
        .thenReturn(null);

    dispatcher.dispatch(command);

    verify(commandGateway, times(2)).sendAndWait(command);
  }

  @Test
  void givenWrappedUniqueConstraintViolation_whenDispatch_thenRetriesOnce() {
    when(commandGateway.sendAndWait(command))
        .thenThrow(
            new CommandExecutionException(
                "command failed",
                new DataIntegrityViolationException(
                    "duplicate key", new SQLException("duplicate key value", "23505"))))
        .thenReturn(null);

    dispatcher.dispatch(command);

    verify(commandGateway, times(2)).sendAndWait(command);
  }

  @Test
  void givenOtherDataIntegrityViolation_whenDispatch_thenPropagatesWithoutRetry() {
    DataIntegrityViolationException failure =
        new DataIntegrityViolationException(
            "foreign key violation", new SQLException("missing reference", "23503"));
    when(commandGateway.sendAndWait(command)).thenThrow(failure);

    assertThatThrownBy(() -> dispatcher.dispatch(command)).isSameAs(failure);
    verify(commandGateway, times(1)).sendAndWait(command);
  }

  @Test
  void givenNonRetryableException_whenDispatch_thenPropagatesWithoutRetry() {
    ResourceNotFoundException failure = new ResourceNotFoundException("not found");
    when(commandGateway.sendAndWait(command)).thenThrow(failure);

    assertThatThrownBy(() -> dispatcher.dispatch(command)).isSameAs(failure);
    verify(commandGateway, times(1)).sendAndWait(command);
  }

  @Test
  void givenWrappedApplicationVersionConflict_whenDispatch_thenPropagatesItsCauseWithoutRetry() {
    ApplicationVersionConflictException failure = new ApplicationVersionConflictException(null, 0L);
    when(commandGateway.sendAndWait(command))
        .thenThrow(new CommandExecutionException("command failed", failure));

    assertThatThrownBy(() -> dispatcher.dispatch(command)).isSameAs(failure);
    verify(commandGateway, times(1)).sendAndWait(command);
  }

  @Test
  void givenRetryAlsoFails_whenDispatch_thenRethrowsWithOriginalSuppressed() {
    ConcurrencyException first = new ConcurrencyException("first");
    ResourceNotFoundException retry = new ResourceNotFoundException("retry failure");
    when(commandGateway.sendAndWait(command)).thenThrow(first).thenThrow(retry);

    assertThatThrownBy(() -> dispatcher.dispatch(command))
        .isInstanceOf(ResourceNotFoundException.class)
        .satisfies(e -> assertThat(e.getSuppressed()).contains(first));
  }

  @Test
  void givenRetryProducesWrappedApplicationVersionConflict_whenDispatch_thenPropagatesItsCause() {
    ConcurrencyException first = new ConcurrencyException("first");
    ApplicationVersionConflictException retry = new ApplicationVersionConflictException(null, 0L);
    when(commandGateway.sendAndWait(command))
        .thenThrow(first)
        .thenThrow(new CommandExecutionException("command failed", retry));

    assertThatThrownBy(() -> dispatcher.dispatch(command))
        .isSameAs(retry)
        .satisfies(e -> assertThat(e.getSuppressed()).contains(first));
  }
}
