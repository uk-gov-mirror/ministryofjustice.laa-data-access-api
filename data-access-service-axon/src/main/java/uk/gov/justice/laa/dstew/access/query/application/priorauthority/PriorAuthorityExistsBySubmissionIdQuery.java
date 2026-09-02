package uk.gov.justice.laa.dstew.access.query.application.priorauthority;

import java.util.UUID;

/** Queries whether a current-state projection exists for a prior-authority submission. */
public record PriorAuthorityExistsBySubmissionIdQuery(UUID submissionId) {}
