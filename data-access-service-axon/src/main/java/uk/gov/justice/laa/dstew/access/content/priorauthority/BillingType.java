package uk.gov.justice.laa.dstew.access.content.priorauthority;

import uk.gov.justice.laa.dstew.access.ExcludeFromGeneratedCodeCoverage;

/** Expert billing types supported by the get-prior-authority use case. */
@ExcludeFromGeneratedCodeCoverage
public enum BillingType {
  HOURLY,
  FIXED_RATE;

  public static BillingType from(String value) {
    return value == null ? null : valueOf(value);
  }
}
