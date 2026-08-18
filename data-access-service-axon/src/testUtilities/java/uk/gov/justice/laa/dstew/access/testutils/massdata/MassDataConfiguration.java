package uk.gov.justice.laa.dstew.access.testutils.massdata;

import java.nio.file.Path;
import java.util.OptionalLong;

public record MassDataConfiguration(
    int count, int maxWorkers, OptionalLong seed, Path dumpPath, int progressInterval) {

  public static MassDataConfiguration fromSystemProperties() {
    String countValue = System.getProperty("massDataCount");
    if (countValue == null || countValue.isBlank()) {
      //      throw new IllegalArgumentException("massDataCount is required and must be at least
      // 1");
      countValue = "1";
    }
    int count = positiveInteger("massDataCount", countValue);
    int maxWorkers =
        positiveInteger("massDataMaxWorkers", System.getProperty("massDataMaxWorkers", "10"));
    int progressInterval =
        positiveInteger(
            "massDataProgressInterval", System.getProperty("massDataProgressInterval", "100"));
    String seedValue = System.getProperty("massDataSeed");
    OptionalLong seed =
        seedValue == null || seedValue.isBlank()
            ? OptionalLong.empty()
            : OptionalLong.of(Long.parseLong(seedValue));
    return new MassDataConfiguration(
        count,
        maxWorkers,
        seed,
        Path.of(System.getProperty("massDataDump", "build/generated-dumps/axon-mass-data.dump")),
        progressInterval);
  }

  private static int positiveInteger(String property, String value) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 1) {
        throw new IllegalArgumentException(property + " must be at least 1");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(property + " must be an integer", exception);
    }
  }
}
