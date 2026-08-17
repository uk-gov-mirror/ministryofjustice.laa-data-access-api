package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGeneratorFactory;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import uk.gov.justice.laa.dstew.access.command.application.CreateApplicationUseCase;
import uk.gov.justice.laa.dstew.access.command.application.assignment.AssignCaseworkerUseCase;
import uk.gov.justice.laa.dstew.access.command.application.assignment.UnassignCaseworkerUseCase;
import uk.gov.justice.laa.dstew.access.command.application.decision.MakeApplicationDecisionUseCase;
import uk.gov.justice.laa.dstew.access.command.application.ready.RecordAutoGrantOutcomeUseCase;
import uk.gov.justice.laa.dstew.access.command.caseworker.Caseworker;
import uk.gov.justice.laa.dstew.access.command.caseworker.CaseworkerRepository;
import uk.gov.justice.laa.dstew.access.controller.application.AssignCaseworkerRequestMapper;
import uk.gov.justice.laa.dstew.access.controller.application.AutoGrantOutcomeCommandMapper;
import uk.gov.justice.laa.dstew.access.controller.application.CreateApplicationCommandMapper;
import uk.gov.justice.laa.dstew.access.controller.application.MakeDecisionCommandMapper;
import uk.gov.justice.laa.dstew.access.controller.application.UnassignCaseworkerRequestMapper;
import uk.gov.justice.laa.dstew.access.query.application.ApplicationReadModel;
import uk.gov.justice.laa.dstew.access.query.application.FindApplicationByIdQuery;
import uk.gov.justice.laa.dstew.access.testutils.massdata.ApplicationLifecycle;
import uk.gov.justice.laa.dstew.access.testutils.massdata.ApplicationLifecycleSelector;
import uk.gov.justice.laa.dstew.access.testutils.massdata.GeneratedRequestFactory;
import uk.gov.justice.laa.dstew.access.testutils.massdata.GenerationSummary;
import uk.gov.justice.laa.dstew.access.testutils.massdata.MassDataConfiguration;
import uk.gov.justice.laa.dstew.access.validation.ValidationException;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerateAxonMassDataDumpTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private QueryGateway queryGateway;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CaseworkerRepository caseworkerRepository;
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
  void generatesAndExportsMassData() throws Exception {
    MassDataConfiguration configuration = MassDataConfiguration.fromSystemProperties();
    Instant startedAt = Instant.now();
    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'axon'
                  AND table_name IN ('domain_event_entry', 'application_current_state')
                """,
                Integer.class))
        .isEqualTo(2);

    List<UUID> caseworkerIds = initialiseCaseworkers(25);
    String runId = UUID.randomUUID().toString().substring(0, 8);
    GeneratedRequestFactory requests = new GeneratedRequestFactory(runId);
    ApplicationLifecycleSelector lifecycleSelector = new ApplicationLifecycleSelector();
    GenerationSummary summary = new GenerationSummary();
    List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    ExecutorService executor = Executors.newFixedThreadPool(configuration.workers());
    try {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int index = 0; index < configuration.count(); index++) {
        int applicationIndex = index;
        tasks.add(
            () -> {
              generateApplication(
                  applicationIndex,
                  configuration,
                  requests,
                  lifecycleSelector,
                  caseworkerIds,
                  summary,
                  failures);
              return null;
            });
      }
      List<Future<Void>> futures = executor.invokeAll(tasks);
      for (Future<Void> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
        executor.shutdownNow();
        fail("Mass-data workers did not terminate within 30 minutes");
      }
    }

    assertThat(failures).as("generation failures").isEmpty();
    Counts counts = awaitProjectionCounts(configuration.count());
    writeDumpAndMetadata(configuration, counts, Duration.between(startedAt, Instant.now()));
  }

  private void generateApplication(
      int index,
      MassDataConfiguration configuration,
      GeneratedRequestFactory requests,
      ApplicationLifecycleSelector lifecycleSelector,
      List<UUID> caseworkerIds,
      GenerationSummary summary,
      List<String> failures) {
    UUID applicationId = UUID.randomUUID();
    var random =
        RandomGeneratorFactory.getDefault()
            .create(configuration.seed().orElseGet(() -> java.util.concurrent.ThreadLocalRandom.current().nextLong()) ^ index);
    ApplicationLifecycle lifecycle = lifecycleSelector.select(random);
    summary.submitted();
    try {
      boolean projectionFound =
          createApplicationUseCase.execute(createApplicationCommandMapper.toCommand(requests.application(applicationId, index), 1));
      if (!projectionFound) {
        throw new IllegalStateException("Create projection did not become available");
      }
      if (lifecycle.autoGranted()) {
        recordAutoGrantOutcomeUseCase.record(autoGrantOutcomeCommandMapper.toCommand(applicationId, requests.autoGrant()));
      } else {
        if (lifecycle.makeDecision()) {
          UUID proceedingId =
              queryGateway
                  .query(new FindApplicationByIdQuery(applicationId), ApplicationReadModel.class)
                  .join()
                  .getProceedings()
                  .getFirst()
                  .proceedingId();
          makeApplicationDecisionUseCase.execute(
              makeDecisionCommandMapper.toCommand(applicationId, requests.decision(proceedingId)));
        }
        if (lifecycle.assignCaseworker()) {
          UUID caseworkerId = caseworkerIds.get(random.nextInt(caseworkerIds.size()));
          var assignment = assignCaseworkerRequestMapper.toAssignment(requests.assignment(applicationId, caseworkerId));
          assignCaseworkerUseCase.assign(
              assignment.caseworkerId(), assignment.applicationId(), assignment.serialisedRequest(), assignment.eventDescription());
        }
        if (lifecycle.unassignCaseworker()) {
          unassignCaseworkerUseCase.execute(unassignCaseworkerRequestMapper.toCommand(applicationId, requests.unassignment()));
        }
      }
      summary.succeeded();
      if (summary.submittedCount() % configuration.progressInterval() == 0) {
        System.out.printf("Generated %d of %d applications%n", summary.submittedCount(), configuration.count());
      }
    } catch (Exception exception) {
      summary.failed();
      String details =
        exception instanceof ValidationException validationException
          ? validationException.errors().toString()
          : exception.toString();
      failures.add(
        "index="
          + index
          + ", applicationId="
          + applicationId
          + ", lifecycle="
          + lifecycle
          + ": "
          + details);
    }
  }

  private List<UUID> initialiseCaseworkers(int size) {
    List<UUID> ids = caseworkerRepository.findAll().stream().map(Caseworker::getId).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    while (ids.size() < size) {
      UUID id = UUID.randomUUID();
      caseworkerRepository.save(new Caseworker(id, "axon-mass-data-" + ids.size()));
      ids.add(id);
    }
    return List.copyOf(ids);
  }

  private Counts awaitProjectionCounts(int expectedApplications) {
    Awaitility.await()
        .atMost(Duration.ofMinutes(2))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () -> {
              Counts counts = queryCounts();
              assertThat(counts.applicationCurrentState()).isGreaterThanOrEqualTo((long) expectedApplications);
              assertThat(counts.domainEventEntry()).isGreaterThanOrEqualTo((long) expectedApplications);
            });
    return queryCounts();
  }

  private Counts queryCounts() {
    return new Counts(
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM axon.domain_event_entry", Long.class),
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM axon.application_current_state", Long.class),
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM axon.application_history", Long.class),
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM axon.application_list_index", Long.class));
  }

  private void writeDumpAndMetadata(MassDataConfiguration configuration, Counts counts, Duration duration)
      throws Exception {
    Files.createDirectories(configuration.dumpPath().toAbsolutePath().getParent());
    var result =
        postgres.execInContainer(
            "pg_dump", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(), "--format=custom", "--file=/tmp/axon-mass-data.dump");
    assertThat(result.getExitCode()).withFailMessage("pg_dump failed:%n%s%n%s", result.getStdout(), result.getStderr()).isZero();
    postgres.copyFileFromContainer("/tmp/axon-mass-data.dump", configuration.dumpPath().toString());
    assertThat(Files.size(configuration.dumpPath())).isPositive();

    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("applicationCount", configuration.count());
    metadata.put("workers", configuration.workers());
    metadata.put("randomSeed", configuration.seed().isPresent() ? configuration.seed().getAsLong() : null);
    metadata.put("durationSeconds", duration.toSeconds());
    metadata.put("domainEventEntryCount", counts.domainEventEntry());
    metadata.put("applicationCurrentStateCount", counts.applicationCurrentState());
    metadata.put("applicationHistoryCount", counts.applicationHistory());
    metadata.put("applicationListIndexCount", counts.applicationListIndex());
    metadata.put("postgresImage", "postgres:17-alpine");
    metadata.put("schema", "axon");
    Files.writeString(
        configuration.dumpPath().resolveSibling(configuration.dumpPath().getFileName().toString().replaceFirst("\\.dump$", ".metadata.json")),
        objectMapper.writeValueAsString(metadata));
  }

  private record Counts(
      long domainEventEntry,
      long applicationCurrentState,
      long applicationHistory,
      long applicationListIndex) {}
}