package uk.gov.justice.laa.dstew.access.config.interceptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.justice.laa.dstew.access.testutils.ApplicationCreateRequestFixture.validApplicationContent;

import java.time.Instant;
import java.util.UUID;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.Test;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationCommand;
import uk.gov.justice.laa.dstew.access.command.application.priorauthority.CreatePriorAuthorityCommand;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselDetails;
import uk.gov.justice.laa.dstew.access.content.priorauthority.CounselType;
import uk.gov.justice.laa.dstew.access.content.priorauthority.PriorAuthorityContent;
import uk.gov.justice.laa.dstew.access.validation.JsonSchemaValidator;

class ContentSchemaValidationDispatchInterceptorTest {

  @Test
  void givenCcsCreateCommand_whenDispatched_thenValidatesWithCommandSchema() {
    JsonSchemaValidator validator = mock(JsonSchemaValidator.class);
    ContentSchemaValidationDispatchInterceptor interceptor =
        new ContentSchemaValidationDispatchInterceptor(validator);
    UUID id = UUID.randomUUID();
    CreateApplicationCommand command = createApplicationCommand(id, "CssApplication.json");
    var commandMessage =
        new GenericCommandMessage(new MessageType(CreateApplicationCommand.class), command);
    MessageDispatchInterceptorChain<CommandMessage> chain = chain();

    interceptor.interceptOnDispatch(commandMessage, null, chain);

    verify(validator).validate(command.applicationContent(), "CssApplication.json", 1);
    verify(chain).proceed(commandMessage, null);
  }

  @Test
  void givenPriorAuthorityCommand_whenDispatched_thenValidatesCommandContent() {
    JsonSchemaValidator validator = mock(JsonSchemaValidator.class);
    ContentSchemaValidationDispatchInterceptor interceptor =
        new ContentSchemaValidationDispatchInterceptor(validator);
    CreatePriorAuthorityCommand command =
        new CreatePriorAuthorityCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new PriorAuthorityContent(
                "COUNSEL",
                "Need specialist counsel",
                null,
                new CounselDetails(CounselType.KINGS_COUNSEL_ALONE),
                null),
            "{}",
            1,
            "PriorAuthority.json",
            Instant.now());
    var commandMessage =
        new GenericCommandMessage(new MessageType(CreatePriorAuthorityCommand.class), command);
    MessageDispatchInterceptorChain<CommandMessage> chain = chain();

    interceptor.interceptOnDispatch(commandMessage, null, chain);

    verify(validator).validate(command.content(), "PriorAuthority.json", 1);
    verify(chain).proceed(commandMessage, null);
  }

  @Test
  void givenNonCreateCommand_whenDispatched_thenSkipsSchemaValidation() {
    JsonSchemaValidator validator = mock(JsonSchemaValidator.class);
    ContentSchemaValidationDispatchInterceptor interceptor =
        new ContentSchemaValidationDispatchInterceptor(validator);
    var commandMessage =
        new GenericCommandMessage(new MessageType(String.class), "not a create command");
    MessageDispatchInterceptorChain<CommandMessage> chain = chain();

    interceptor.interceptOnDispatch(commandMessage, null, chain);

    verifyNoInteractions(validator);
    verify(chain).proceed(commandMessage, null);
  }

  @SuppressWarnings("unchecked")
  private MessageDispatchInterceptorChain<CommandMessage> chain() {
    MessageDispatchInterceptorChain<CommandMessage> chain =
        mock(MessageDispatchInterceptorChain.class);
    when(chain.proceed(any(), isNull())).thenReturn(mock(MessageStream.class));
    return chain;
  }

  private CreateApplicationCommand createApplicationCommand(UUID id, String schemaName) {
    return new CreateApplicationCommand(
        id,
        "APPLICATION_SUBMITTED",
        "LAA-123",
        validApplicationContent(id, UUID.randomUUID()),
        "{}",
        1,
        schemaName);
  }
}
