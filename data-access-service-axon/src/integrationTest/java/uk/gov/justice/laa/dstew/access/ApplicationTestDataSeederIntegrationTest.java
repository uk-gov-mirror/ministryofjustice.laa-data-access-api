package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import uk.gov.justice.laa.dstew.access.command.application.AutoGrantedState;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadModel;
import uk.gov.justice.laa.dstew.access.query.application.FindApplicationByIdQuery;
import uk.gov.justice.laa.dstew.access.testutils.ApplicationLifecycle;
import uk.gov.justice.laa.dstew.access.testutils.GeneratedRequestFactory;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTestDataSeederIntegrationTest
    extends AbstractApplicationTestSeederIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Test
  void seedsAnAutoGrantedApplication() {
    UUID applicationId = UUID.randomUUID();
    var seeder = newSeeder();

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
                      .query(
                          new FindApplicationByIdQuery(applicationId), ApplicationReadModel.class)
                      .join();
              assertThat(application.getAutoGranted()).isEqualTo(AutoGrantedState.AUTOGRANTED);
              assertThat(application.getDecisionStatus()).isEqualTo("GRANTED");
            });
  }
}
