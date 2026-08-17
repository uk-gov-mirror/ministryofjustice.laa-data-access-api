package uk.gov.justice.laa.dstew.access.testutils.massdata;

public record ApplicationLifecycle(
    boolean autoGranted,
    boolean makeDecision,
    boolean assignCaseworker,
    boolean unassignCaseworker) {

  public ApplicationLifecycle {
    if (autoGranted && (makeDecision || assignCaseworker || unassignCaseworker)) {
      throw new IllegalArgumentException(
          "Auto-granted applications cannot have further lifecycle actions");
    }
    if (unassignCaseworker && !assignCaseworker) {
      throw new IllegalArgumentException("Cannot unassign without prior assignment");
    }
  }
}
