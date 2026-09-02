package uk.gov.justice.laa.dstew.access.content.priorauthority;

import uk.gov.justice.laa.dstew.access.ExcludeFromGeneratedCodeCoverage;

/** Counsel types supported by the get-prior-authority use case. */
@ExcludeFromGeneratedCodeCoverage
public enum CounselType {
  KINGS_COUNSEL_ALONE,
  TWO_JUNIOR_COUNSEL,
  KINGS_COUNSEL_AND_JUNIOR_COUNSEL,
  KINGS_COUNSEL_AND_TWO_JUNIOR_COUNSEL;

  public static CounselType from(String value) {
    return value == null ? null : valueOf(value);
  }
}
