package uk.gov.justice.laa.dstew.access;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.beans.factory.annotation.Autowired;
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
import uk.gov.justice.laa.dstew.access.testutils.ApplicationTestDataSeeder;

abstract class AbstractApplicationTestSeederIntegrationTest {

  @Autowired protected QueryGateway queryGateway;
  @Autowired protected CreateApplicationUseCase createApplicationUseCase;
  @Autowired protected MakeApplicationDecisionUseCase makeApplicationDecisionUseCase;
  @Autowired protected RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase;
  @Autowired protected AssignCaseworkerUseCase assignCaseworkerUseCase;
  @Autowired protected UnassignCaseworkerUseCase unassignCaseworkerUseCase;
  @Autowired protected CreateApplicationCommandMapper createApplicationCommandMapper;
  @Autowired protected MakeDecisionCommandMapper makeDecisionCommandMapper;
  @Autowired protected AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper;
  @Autowired protected AssignCaseworkerRequestMapper assignCaseworkerRequestMapper;
  @Autowired protected UnassignCaseworkerRequestMapper unassignCaseworkerRequestMapper;

  protected ApplicationTestDataSeeder newSeeder() {
    return new ApplicationTestDataSeeder(
        queryGateway,
        createApplicationUseCase,
        makeApplicationDecisionUseCase,
        recordAutoGrantOutcomeUseCase,
        assignCaseworkerUseCase,
        unassignCaseworkerUseCase,
        createApplicationCommandMapper,
        makeDecisionCommandMapper,
        autoGrantOutcomeCommandMapper,
        assignCaseworkerRequestMapper,
        unassignCaseworkerRequestMapper);
  }
}
