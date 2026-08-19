package uk.gov.justice.laa.dstew.access.command.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.justice.laa.dstew.access.command.RetryingCommandDispatcher;

class CreateApplicationUseCaseTest {

  private RetryingCommandDispatcher dispatcher;
  private CreateApplicationUseCase useCase;

  @BeforeEach
  void setUp() {
    dispatcher = mock(RetryingCommandDispatcher.class);
    useCase = new CreateApplicationUseCase(dispatcher);
  }

  @Test
  void givenCommand_WhenExecute_thenDispatchesViaRetryingDispatcher() {
    CreateApplicationCommand command = stubCommand();
    useCase.execute(command);

    verify(dispatcher).dispatch(command);
  }

  private CreateApplicationCommand stubCommand() {
    UUID id = UUID.randomUUID();
    return new CreateApplicationCommand(
        id,
        "APPLICATION_SUBMITTED",
        "LAA-123",
        Map.of("id", id.toString()),
        "{}",
        1,
        "BaseCivilApplication.json");
  }
}
