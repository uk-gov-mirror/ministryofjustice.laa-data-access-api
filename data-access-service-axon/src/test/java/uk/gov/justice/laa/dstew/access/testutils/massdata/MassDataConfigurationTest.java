package uk.gov.justice.laa.dstew.access.testutils.massdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MassDataConfigurationTest {
  private static final List<String> PROPERTIES =
      List.of(
          "massDataCount",
          "massDataWorkers",
          "massDataMaxWorkers",
          "massDataSeed",
          "massDataDump",
          "massDataProgressInterval");

  @AfterEach
  void clearProperties() {
    PROPERTIES.forEach(System::clearProperty);
  }

  @Test
  void usesTheRenamedMaximumWorkerProperty() {
    System.setProperty("massDataCount", "1");
    System.setProperty("massDataMaxWorkers", "2");

    MassDataConfiguration configuration = MassDataConfiguration.fromSystemProperties();

    assertThat(configuration.maxWorkers()).isEqualTo(2);
  }

  @Test
  void ignoresTheRemovedWorkerProperty() {
    System.setProperty("massDataCount", "1");
    System.setProperty("massDataWorkers", "2");

    MassDataConfiguration configuration = MassDataConfiguration.fromSystemProperties();

    assertThat(configuration.maxWorkers()).isEqualTo(10);
  }
}