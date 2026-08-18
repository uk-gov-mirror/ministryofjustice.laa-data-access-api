package uk.gov.justice.laa.dstew.access.testutils;

import java.util.UUID;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.command.application.assignment.AssignCaseworkerUseCase;
import uk.gov.justice.laa.dstew.access.command.application.assignment.UnassignCaseworkerUseCase;
import uk.gov.justice.laa.dstew.access.command.application.decision.MakeApplicationDecisionUseCase;
import uk.gov.justice.laa.dstew.access.command.application.ready.RecordAutoGrantOutcomeUseCase;
import uk.gov.justice.laa.dstew.access.controller.application.AssignCaseworkerRequestMapper;
import uk.gov.justice.laa.dstew.access.controller.application.AutoGrantOutcomeCommandMapper;
import uk.gov.justice.laa.dstew.access.controller.application.CreateApplicationCommandMapper;
import uk.gov.justice.laa.dstew.access.controller.application.MakeDecisionCommandMapper;
import uk.gov.justice.laa.dstew.access.controller.application.UnassignCaseworkerRequestMapper;
import uk.gov.justice.laa.dstew.access.model.ApplicationCreateRequest;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadModel;
import uk.gov.justice.laa.dstew.access.query.application.FindApplicationByIdQuery;

/** Creates valid application examples in explicit lifecycle states for integration tests. */
public class ApplicationTestDataSeeder {
  private final QueryGateway queryGateway;
  private final CreateApplicationUseCase createApplicationUseCase;
  private final MakeApplicationDecisionUseCase makeApplicationDecisionUseCase;
  private final RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase;
  private final AssignCaseworkerUseCase assignCaseworkerUseCase;
  private final UnassignCaseworkerUseCase unassignCaseworkerUseCase;
  private final CreateApplicationCommandMapper createApplicationCommandMapper;
  private final MakeDecisionCommandMapper makeDecisionCommandMapper;
  private final AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper;
  private final AssignCaseworkerRequestMapper assignCaseworkerRequestMapper;
  private final UnassignCaseworkerRequestMapper unassignCaseworkerRequestMapper;

  public ApplicationTestDataSeeder(
      QueryGateway queryGateway,
      CreateApplicationUseCase createApplicationUseCase,
      MakeApplicationDecisionUseCase makeApplicationDecisionUseCase,
      RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase,
      AssignCaseworkerUseCase assignCaseworkerUseCase,
      UnassignCaseworkerUseCase unassignCaseworkerUseCase,
      CreateApplicationCommandMapper createApplicationCommandMapper,
      MakeDecisionCommandMapper makeDecisionCommandMapper,
      AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper,
      AssignCaseworkerRequestMapper assignCaseworkerRequestMapper,
      UnassignCaseworkerRequestMapper unassignCaseworkerRequestMapper) {
    this.queryGateway = queryGateway;
    this.createApplicationUseCase = createApplicationUseCase;
    this.makeApplicationDecisionUseCase = makeApplicationDecisionUseCase;
    this.recordAutoGrantOutcomeUseCase = recordAutoGrantOutcomeUseCase;
    this.assignCaseworkerUseCase = assignCaseworkerUseCase;
    this.unassignCaseworkerUseCase = unassignCaseworkerUseCase;
    this.createApplicationCommandMapper = createApplicationCommandMapper;
    this.makeDecisionCommandMapper = makeDecisionCommandMapper;
    this.autoGrantOutcomeCommandMapper = autoGrantOutcomeCommandMapper;
    this.assignCaseworkerRequestMapper = assignCaseworkerRequestMapper;
    this.unassignCaseworkerRequestMapper = unassignCaseworkerRequestMapper;
  }

  public void seed(
      UUID applicationId, ApplicationCreateRequest request, ApplicationLifecycle lifecycle) {
    seed(applicationId, request, lifecycle, null);
  }

  public void seed(
      UUID applicationId,
      ApplicationCreateRequest request,
      ApplicationLifecycle lifecycle,
      UUID caseworkerId) {
    boolean projectionFound =
        createApplicationUseCase.execute(createApplicationCommandMapper.toCommand(request, 1));
    if (!projectionFound) {
      throw new IllegalStateException("Create projection did not become available");
    }
    if (lifecycle.autoGranted()) {
      recordAutoGrantOutcomeUseCase.record(
          autoGrantOutcomeCommandMapper.toCommand(
              applicationId, new GeneratedRequestFactory("").autoGrant()));
      return;
    }
    if (lifecycle.makeDecision()) {
      UUID proceedingId =
          queryGateway
              .query(new FindApplicationByIdQuery(applicationId), ApplicationReadModel.class)
              .join()
              .getProceedings()
              .getFirst()
              .proceedingId();
      makeApplicationDecisionUseCase.execute(
          makeDecisionCommandMapper.toCommand(
              applicationId, new GeneratedRequestFactory("").decision(proceedingId)));
    }
    if (lifecycle.assignCaseworker()) {
      UUID assignedCaseworkerId =
          java.util.Objects.requireNonNull(caseworkerId, "caseworkerId is required");
      var assignment =
          assignCaseworkerRequestMapper.toAssignment(
              new GeneratedRequestFactory("").assignment(applicationId, assignedCaseworkerId));
      assignCaseworkerUseCase.assign(
          assignment.caseworkerId(),
          assignment.applicationId(),
          assignment.serialisedRequest(),
          assignment.eventDescription());
    }
    if (lifecycle.unassignCaseworker()) {
      unassignCaseworkerUseCase.execute(
          unassignCaseworkerRequestMapper.toCommand(
              applicationId, new GeneratedRequestFactory("").unassignment()));
    }
  }
}
