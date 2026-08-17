package uk.gov.justice.laa.dstew.access.testutils.massdata;

import java.util.random.RandomGenerator;

public class ApplicationLifecycleSelector {
  public ApplicationLifecycle select(RandomGenerator random) {
    if (random.nextDouble() < 0.175) {
      return new ApplicationLifecycle(true, false, false, false);
    }
    boolean decision = random.nextDouble() < 0.40;
    boolean assignment = random.nextDouble() < 0.70;
    return new ApplicationLifecycle(false, decision, assignment, assignment && random.nextDouble() < 0.15);
  }
}