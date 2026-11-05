package com.meridian.realestate.domain;

/**
 * The outcome of a single screening check.
 *
 * <p>Exactly the values the {@code ck_application_screenings_status} constraint allows. The
 * {@code ck_application_screenings_performed} constraint ties the performer columns to this value -
 * a {@code PENDING} check has neither a performer nor a time, and any other status must have both,
 * because a {@code WAIVED} check is an explicit decision and records who made it rather than an
 * absence of a check.</p>
 */
public enum ScreeningStatus {
  /** Not yet run. */
  PENDING,
  /** Run and passed. */
  PASSED,
  /** Run and failed. */
  FAILED,
  /** Deliberately not run, by a recorded decision. */
  WAIVED
}
