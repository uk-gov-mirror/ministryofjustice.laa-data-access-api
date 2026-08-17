package uk.gov.justice.laa.dstew.access.testutils;

/**
 * Valid lifecycle actions for a generated application. Auto-granted applications have no further
 * actions, and unassignment requires a prior assignment.
 */
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