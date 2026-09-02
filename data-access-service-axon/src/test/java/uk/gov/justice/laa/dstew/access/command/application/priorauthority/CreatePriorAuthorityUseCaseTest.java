package uk.gov.justice.laa.dstew.access.command.application.priorauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import uk.gov.justice.laa.dstew.access.command.RetryingCommandDispatcher;
import uk.gov.justice.laa.dstew.access.query.SubscriptionProjectionGateway;
import uk.gov.justice.laa.dstew.access.query.application.priorauthority.PriorAuthorityExistsBySubmissionIdQuery;
import uk.gov.justice.laa.dstew.access.validation.ValidationException;

class CreatePriorAuthorityUseCaseTest {

  private RetryingCommandDispatcher dispatcher;
  private SubscriptionProjectionGateway projectionGateway;
  private CreatePriorAuthorityUseCase useCase;

  @BeforeEach
  void setUp() {
    dispatcher = mock(RetryingCommandDispatcher.class);
    projectionGateway = mock(SubscriptionProjectionGateway.class);
    useCase = new CreatePriorAuthorityUseCase(dispatcher, projectionGateway);
  }

  @Test
  void givenValidApplication_whenProjectionConfirmed_thenReturnsTrue() {
    CreatePriorAuthorityCommand command = stubCommand();
    when(projectionGateway.awaitProjection(any(), eq(Boolean.class), any())).thenReturn(true);

    boolean result = useCase.execute(command);

    assertThat(result).isTrue();
  }

  @Test
  void givenValidApplication_whenProjectionTimeout_thenReturnsFalse() {
    CreatePriorAuthorityCommand command = stubCommand();
    when(projectionGateway.awaitProjection(any(), eq(Boolean.class), any())).thenReturn(false);

    boolean result = useCase.execute(command);

    assertThat(result).isFalse();
  }

  @Test
  void givenValidApplication_whenExecute_thenDispatchesValidationBeforeProjectionGateway() {
    CreatePriorAuthorityCommand command = stubCommand();
    when(projectionGateway.awaitProjection(any(), eq(Boolean.class), any())).thenReturn(true);

    useCase.execute(command);

    InOrder order = Mockito.inOrder(dispatcher, projectionGateway);
    order
        .verify(dispatcher)
        .dispatch(new ValidateApplicationGrantedCommand(command.applicationId()));
    order.verify(projectionGateway).awaitProjection(any(), any(), any());
  }

  @Test
  void givenValidApplication_whenExecute_thenPassesExactQueryAndModelClass() {
    CreatePriorAuthorityCommand command = stubCommand();
    when(projectionGateway.awaitProjection(any(), eq(Boolean.class), any())).thenReturn(true);

    useCase.execute(command);

    verify(projectionGateway)
        .awaitProjection(
            eq(new PriorAuthorityExistsBySubmissionIdQuery(command.submissionId())),
            eq(Boolean.class),
            any());
  }

  @Test
  void givenValidApplication_whenExecute_thenSupplierDispatchesCreateCommand() {
    CreatePriorAuthorityCommand command = stubCommand();
    doAnswer(
            invocation -> {
              Runnable action = invocation.getArgument(2);
              action.run();
              return true;
            })
        .when(projectionGateway)
        .awaitProjection(any(), eq(Boolean.class), any());

    useCase.execute(command);

    verify(dispatcher).dispatch(command);
  }

  @Test
  void givenValidationFails_whenExecute_thenPropagatesAndSkipsProjectionGateway() {
    CreatePriorAuthorityCommand command = stubCommand();
    ValidationException failure = new ValidationException(List.of("Application must be granted"));
    doThrow(failure)
        .when(dispatcher)
        .dispatch(new ValidateApplicationGrantedCommand(command.applicationId()));

    assertThatThrownBy(() -> useCase.execute(command)).isSameAs(failure);
    verify(projectionGateway, never()).awaitProjection(any(), any(), any());
  }

  private CreatePriorAuthorityCommand stubCommand() {
    return new CreatePriorAuthorityCommand(
        UUID.randomUUID(), UUID.randomUUID(), null, "{}", 1, "PriorAuthority.json", Instant.now());
  }
}
