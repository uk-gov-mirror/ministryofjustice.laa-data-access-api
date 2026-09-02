package uk.gov.justice.laa.dstew.access.command.application.priorauthority;

import org.springframework.stereotype.Component;
import uk.gov.justice.laa.dstew.access.command.RetryingCommandDispatcher;
import uk.gov.justice.laa.dstew.access.query.SubscriptionProjectionGateway;
import uk.gov.justice.laa.dstew.access.query.application.priorauthority.PriorAuthorityExistsBySubmissionIdQuery;
import uk.gov.justice.laa.dstew.access.security.AllowApiCaseworker;

/**
 * Validates application eligibility and dispatches a create-prior-authority command, waiting for
 * the projection to confirm the submission is readable.
 */
@Component
public class CreatePriorAuthorityUseCase {

  private final RetryingCommandDispatcher dispatcher;
  private final SubscriptionProjectionGateway projectionGateway;

  public CreatePriorAuthorityUseCase(
      RetryingCommandDispatcher dispatcher, SubscriptionProjectionGateway projectionGateway) {
    this.dispatcher = dispatcher;
    this.projectionGateway = projectionGateway;
  }

  /**
   * Validates the application is granted, dispatches the command, and waits for the projection to
   * become readable.
   *
   * @return {@code true} when the projection confirms the submission within the configured timeout;
   *     {@code false} on timeout — the command has still committed.
   * @throws RuntimeException propagated from validation if the application is not granted
   */
  @AllowApiCaseworker
  public boolean execute(CreatePriorAuthorityCommand command) {
    dispatcher.dispatch(new ValidateApplicationGrantedCommand(command.applicationId()));
    return projectionGateway.awaitProjection(
        new PriorAuthorityExistsBySubmissionIdQuery(command.submissionId()),
        Boolean.class,
        () -> dispatcher.dispatch(command));
  }
}
