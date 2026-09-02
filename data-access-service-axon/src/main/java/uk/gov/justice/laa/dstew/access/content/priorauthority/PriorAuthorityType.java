package uk.gov.justice.laa.dstew.access.content.priorauthority;

/** Types of prior-authority request supported by the get-prior-authority use case. */
public enum PriorAuthorityType {
  EXPERT,
  DISBURSEMENT,
  COUNSEL;

  public static PriorAuthorityType from(String value) {
    return value == null || value.isEmpty() ? null : valueOf(value);
  }
}
