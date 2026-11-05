package com.meridian.realestate.domain;

/**
 * The state of a rental application.
 *
 * <p>Exactly the values the {@code ck_rental_applications_status} constraint allows. The
 * {@code ck_rental_applications_decision} constraint also ties the decision columns to this value -
 * an approval or rejection must record who decided and when, and a rejection must carry a reason,
 * while an undecided application must carry no decision time at all - so a decision is stored
 * whole or not at all.</p>
 */
public enum ApplicationStatus {
  /** Received, not yet under review. */
  SUBMITTED,
  /** Checks are being run. */
  SCREENING,
  /** Approved, with a decision time and a decider. */
  APPROVED,
  /** Rejected, with a decision time, a decider and a reason. */
  REJECTED,
  /** Withdrawn by the applicant before a decision. */
  WITHDRAWN;

  /** True while the application is undecided, which is the "open" state for the unit rule. */
  public boolean isOpen() {
    return this == SUBMITTED || this == SCREENING;
  }
}
