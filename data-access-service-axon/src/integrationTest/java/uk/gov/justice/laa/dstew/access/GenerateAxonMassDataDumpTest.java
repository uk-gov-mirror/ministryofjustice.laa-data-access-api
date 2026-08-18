package uk.gov.justice.laa.dstew.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.zaxxer.hikari.HikariDataSource;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGeneratorFactory;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import uk.gov.justice.laa.dstew.access.command.caseworker.Caseworker;
import uk.gov.justice.laa.dstew.access.command.caseworker.CaseworkerRepository;
import uk.gov.justice.laa.dstew.access.testutils.ApplicationLifecycle;
import uk.gov.justice.laa.dstew.access.testutils.ApplicationTestDataSeeder;
import uk.gov.justice.laa.dstew.access.testutils.GeneratedRequestFactory;
import uk.gov.justice.laa.dstew.access.testutils.massdata.ApplicationLifecycleSelector;
import uk.gov.justice.laa.dstew.access.testutils.massdata.GenerationSummary;
import uk.gov.justice.laa.dstew.access.testutils.massdata.MassDataConfiguration;
import uk.gov.justice.laa.dstew.access.validation.ValidationException;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerateAxonMassDataDumpTest extends AbstractApplicationTestSeederIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private DataSource dataSource;
  @Autowired private Environment environment;
  @Autowired private CaseworkerRepository caseworkerRepository;

  @Test
  void generatesAndExportsMassData() throws Exception {
    MassDataConfiguration configuration = MassDataConfiguration.fromSystemProperties();
    WorkerSettings workerSettings = resolveWorkerSettings(configuration.maxWorkers());
    Instant startedAt = Instant.now();
    System.out.printf(
        "Starting Axon mass-data generation: requested=%d, workers=%d, seed=%s, dump=%s%n",
        configuration.count(),
        workerSettings.requestedWorkers(),
        configuration.seed().isPresent() ? configuration.seed().getAsLong() : "random",
        configuration.dumpPath());
    System.out.printf(
        "Worker sizing: requested=%d, availableProcessors=%d, cpuCap=%d, dbPoolSize=%d, effective=%d%n",
        workerSettings.requestedWorkers(),
        workerSettings.availableProcessors(),
        workerSettings.cpuCap(),
        workerSettings.dbPoolSize(),
        workerSettings.effectiveWorkers());
    if (workerSettings.effectiveWorkers() != workerSettings.requestedWorkers()) {
      System.out.printf(
          "Worker cap applied: requested=%d -> effective=%d (cpuCap=%d, dbPoolSize=%d)%n",
          workerSettings.requestedWorkers(),
          workerSettings.effectiveWorkers(),
          workerSettings.cpuCap(),
          workerSettings.dbPoolSize());
    }
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
    ApplicationTestDataSeeder seeder = newSeeder();
    GenerationSummary summary = new GenerationSummary();
    List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    ExecutorService executor = Executors.newFixedThreadPool(workerSettings.effectiveWorkers());
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
                  seeder,
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

    printGenerationSummary(configuration, summary);
    assertThat(failures).as("generation failures").isEmpty();
    Counts counts = awaitProjectionCounts(configuration.count());
    writeDumpAndMetadata(
        configuration, workerSettings, counts, Duration.between(startedAt, Instant.now()));
  }

  private WorkerSettings resolveWorkerSettings(int requestedWorkers) {
    int availableProcessors = Runtime.getRuntime().availableProcessors();
    int cpuCap = Math.max(1, availableProcessors * 2);
    int dbPoolSize = resolveDbPoolSize();
    int effectiveWorkers = Math.max(1, Math.min(requestedWorkers, Math.min(cpuCap, dbPoolSize)));
    System.out.printf("Workers used: %d", effectiveWorkers);
    return new WorkerSettings(
        requestedWorkers, effectiveWorkers, availableProcessors, cpuCap, dbPoolSize);
  }

  private int resolveDbPoolSize() {
    if (dataSource instanceof HikariDataSource hikariDataSource
        && hikariDataSource.getMaximumPoolSize() > 0) {
      return hikariDataSource.getMaximumPoolSize();
    }

    String configuredPoolSize =
        environment.getProperty("spring.datasource.hikari.maximum-pool-size");
    if (configuredPoolSize != null) {
      try {
        int parsedPoolSize = Integer.parseInt(configuredPoolSize);
        if (parsedPoolSize > 0) {
          return parsedPoolSize;
        }
      } catch (NumberFormatException ignored) {
        System.out.printf(
            "Invalid spring.datasource.hikari.maximum-pool-size value '%s'; defaulting dbPoolSize=10%n",
            configuredPoolSize);
      }
    }

    // Align with Hikari's typical default when no explicit maximum pool size is configured.
    return 10;
  }

  private void generateApplication(
      int index,
      MassDataConfiguration configuration,
      GeneratedRequestFactory requests,
      ApplicationLifecycleSelector lifecycleSelector,
      ApplicationTestDataSeeder seeder,
      List<UUID> caseworkerIds,
      GenerationSummary summary,
      List<String> failures) {
    UUID applicationId = UUID.randomUUID();
    var random =
        RandomGeneratorFactory.getDefault()
            .create(
                configuration.seed().orElseGet(() -> ThreadLocalRandom.current().nextLong())
                    ^ index);
    ApplicationLifecycle lifecycle = lifecycleSelector.select(random);
    summary.submitted();
    try {
      if (lifecycle.assignCaseworker()) {
        UUID caseworkerId = caseworkerIds.get(random.nextInt(caseworkerIds.size()));
        seeder.seed(
            applicationId, requests.application(applicationId, index), lifecycle, caseworkerId);
      } else {
        seeder.seed(applicationId, requests.application(applicationId, index), lifecycle);
      }
      summary.succeeded();
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
    } finally {
      summary.completed();
      if (summary.completedCount() % configuration.progressInterval() == 0
          || summary.completedCount() == configuration.count()) {
        System.out.printf(
            "Generation progress: completed=%d/%d, succeeded=%d, failed=%d%n",
            summary.completedCount(),
            configuration.count(),
            summary.succeededCount(),
            summary.failedCount());
      }
    }
  }

  private void printGenerationSummary(
      MassDataConfiguration configuration, GenerationSummary summary) {
    System.out.printf(
        "Generation summary: requested=%d, submitted=%d, completed=%d, succeeded=%d, failed=%d%n",
        configuration.count(),
        summary.submittedCount(),
        summary.completedCount(),
        summary.succeededCount(),
        summary.failedCount());
  }

  private List<UUID> initialiseCaseworkers(int size) {
    List<UUID> ids =
        caseworkerRepository.findAll().stream()
            .map(Caseworker::getId)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
              assertThat(counts.applicationCurrentState())
                  .isGreaterThanOrEqualTo(expectedApplications);
              assertThat(counts.domainEventEntry()).isGreaterThanOrEqualTo(expectedApplications);
            });
    return queryCounts();
  }

  private Counts queryCounts() {
    return new Counts(
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM axon.domain_event_entry", Long.class),
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM axon.application_current_state", Long.class),
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM axon.application_history", Long.class),
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM axon.application_list_index", Long.class));
  }

  private void writeDumpAndMetadata(
      MassDataConfiguration configuration,
      WorkerSettings workerSettings,
      Counts counts,
      Duration duration)
      throws Exception {
    Files.createDirectories(configuration.dumpPath().toAbsolutePath().getParent());
    var result =
        postgres.execInContainer(
            "pg_dump",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "--format=custom",
            "--file=/tmp/axon-mass-data.dump");
    assertThat(result.getExitCode())
        .withFailMessage("pg_dump failed:%n%s%n%s", result.getStdout(), result.getStderr())
        .isZero();
    postgres.copyFileFromContainer("/tmp/axon-mass-data.dump", configuration.dumpPath().toString());
    assertThat(Files.size(configuration.dumpPath())).isPositive();

    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("applicationCount", configuration.count());
    metadata.put("maxWorkers", configuration.maxWorkers());
    metadata.put("requestedWorkers", workerSettings.requestedWorkers());
    metadata.put("effectiveWorkers", workerSettings.effectiveWorkers());
    metadata.put("availableProcessors", workerSettings.availableProcessors());
    metadata.put("cpuCap", workerSettings.cpuCap());
    metadata.put("dbPoolSize", workerSettings.dbPoolSize());
    metadata.put(
        "randomSeed", configuration.seed().isPresent() ? configuration.seed().getAsLong() : null);
    metadata.put("durationSeconds", duration.toSeconds());
    metadata.put("domainEventEntryCount", counts.domainEventEntry());
    metadata.put("applicationCurrentStateCount", counts.applicationCurrentState());
    metadata.put("applicationHistoryCount", counts.applicationHistory());
    metadata.put("applicationListIndexCount", counts.applicationListIndex());
    metadata.put("postgresImage", "postgres:17-alpine");
    metadata.put("schema", "axon");
    Files.writeString(
        configuration
            .dumpPath()
            .resolveSibling(
                configuration
                    .dumpPath()
                    .getFileName()
                    .toString()
                    .replaceFirst("\\.dump$", ".metadata.json")),
        objectMapper.writeValueAsString(metadata));
  }

  private record Counts(
      long domainEventEntry,
      long applicationCurrentState,
      long applicationHistory,
      long applicationListIndex) {}

  private record WorkerSettings(
      int requestedWorkers,
      int effectiveWorkers,
      int availableProcessors,
      int cpuCap,
      int dbPoolSize) {}
}
