package uk.gov.justice.laa.dstew.access.testutils.massdata;

import java.util.concurrent.atomic.LongAdder;

public class GenerationSummary {
  private final LongAdder submitted = new LongAdder();
  private final LongAdder succeeded = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final LongAdder completed = new LongAdder();

  public void submitted() {
    submitted.increment();
  }

  public void succeeded() {
    succeeded.increment();
  }

  public void failed() {
    failed.increment();
  }

  public void completed() {
    completed.increment();
  }

  public long submittedCount() {
    return submitted.sum();
  }

  public long succeededCount() {
    return succeeded.sum();
  }

  public long failedCount() {
    return failed.sum();
  }

  public long completedCount() {
    return completed.sum();
  }
}
