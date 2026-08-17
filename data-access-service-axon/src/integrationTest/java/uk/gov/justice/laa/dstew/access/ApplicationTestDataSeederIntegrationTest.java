package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import uk.gov.justice.laa.dstew.access.command.application.AutoGrantedState;
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
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadModel;
import uk.gov.justice.laa.dstew.access.query.application.FindApplicationByIdQuery;
import uk.gov.justice.laa.dstew.access.testutils.ApplicationLifecycle;
import uk.gov.justice.laa.dstew.access.testutils.ApplicationTestDataSeeder;
import uk.gov.justice.laa.dstew.access.testutils.GeneratedRequestFactory;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTestDataSeederIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired private QueryGateway queryGateway;
  @Autowired private CreateApplicationUseCase createApplicationUseCase;
  @Autowired private MakeApplicationDecisionUseCase makeApplicationDecisionUseCase;
  @Autowired private RecordAutoGrantOutcomeUseCase recordAutoGrantOutcomeUseCase;
  @Autowired private AssignCaseworkerUseCase assignCaseworkerUseCase;
  @Autowired private UnassignCaseworkerUseCase unassignCaseworkerUseCase;
  @Autowired private CreateApplicationCommandMapper createApplicationCommandMapper;
  @Autowired private MakeDecisionCommandMapper makeDecisionCommandMapper;
  @Autowired private AutoGrantOutcomeCommandMapper autoGrantOutcomeCommandMapper;
  @Autowired private AssignCaseworkerRequestMapper assignCaseworkerRequestMapper;
  @Autowired private UnassignCaseworkerRequestMapper unassignCaseworkerRequestMapper;

  @Test
  void seedsAnAutoGrantedApplication() {
    UUID applicationId = UUID.randomUUID();
    ApplicationTestDataSeeder seeder =
        new ApplicationTestDataSeeder(
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

    seeder.seed(
        applicationId,
        new GeneratedRequestFactory("integration-test").application(applicationId, 1),
      new ApplicationLifecycle(true, false, false, false));

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              ApplicationReadModel application =
                  queryGateway
                      .query(new FindApplicationByIdQuery(applicationId), ApplicationReadModel.class)
                      .join();
              assertThat(application.getAutoGranted()).isEqualTo(AutoGrantedState.AUTOGRANTED);
              assertThat(application.getDecisionStatus()).isEqualTo("GRANTED");
            });
  }
}