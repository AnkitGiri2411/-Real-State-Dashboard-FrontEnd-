package com.meridian.realestate.domain;

/**
 * The state of a maintenance request as it moves from report to closure.
 *
 * <p>Exactly the values the {@code ck_maintenance_requests_status} constraint allows. The same enum
 * types the {@code from_status} / {@code to_status} pair on
 * {@link MaintenanceStatusHistory}, so a recorded transition cannot name a state the request itself
 * could not be in.</p>
 *
 * <p>Three states are outstanding work: {@code OPEN}, {@code ASSIGNED} and {@code IN_PROGRESS}.
 * That set is the one the schema's partial index {@code ix_maintenance_requests_open} is built over
 * and the one the dashboard's open and urgent counts read - see {@link #isOpen()}. The remaining
 * three are settled: {@code RESOLVED} and {@code CLOSED} ended in work done, {@code CANCELLED} ended
 * in nothing being done at all, which is a real outcome and not a kind of open.</p>
 */
public enum MaintenanceStatus {
  /** Reported, not yet given to anybody. */
  OPEN,
  /** Handed to a vendor or an internal colleague; the schema requires a named assignee. */
  ASSIGNED,
  /** Work has begun; the schema requires a start moment. */
  IN_PROGRESS,
  /** Fixed. The schema requires a resolution moment and notes. */
  RESOLVED,
  /** Fixed and administratively closed; closure cannot precede resolution. */
  CLOSED,
  /** Dropped without work being done. */
  CANCELLED;

  /**
   * True when the request is still outstanding.
   *
   * <p>This is the open set: {@code OPEN}, {@code ASSIGNED}, {@code IN_PROGRESS}. It is exactly the
   * predicate of the partial index the open-work board and the urgent KPI are served from.</p>
   */
  public boolean isOpen() {
    return this == OPEN || this == ASSIGNED || this == IN_PROGRESS;
  }
}
