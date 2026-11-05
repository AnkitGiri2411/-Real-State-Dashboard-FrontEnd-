package com.meridian.realestate.domain;

/**
 * The state of a viewing appointment.
 *
 * <p>Exactly the values the {@code ck_viewings_status} constraint allows.</p>
 *
 * <p><strong>The outcome is not a lone column.</strong> The {@code ck_viewings_outcome} constraint
 * refuses a half-applied outcome: a {@code COMPLETED} viewing must carry both {@code completed_at}
 * and {@code feedback}; a {@code CANCELLED} or {@code NO_SHOW} viewing must carry a
 * {@code cancelled_reason} and must not carry a completion time; a {@code SCHEDULED} or
 * {@code CONFIRMED} viewing must not be completed. A lifecycle engine must therefore set the status
 * and the matching outcome columns <em>together</em>, in one statement, because any intermediate
 * state - for example status set first and feedback written after - is rejected by the
 * database.</p>
 */
public enum ViewingStatus {
  /** Booked, not yet confirmed with the prospect. */
  SCHEDULED,
  /** Confirmed with the prospect. */
  CONFIRMED,
  /** Held, with a completion time and feedback recorded. */
  COMPLETED,
  /** Called off, with a reason recorded. */
  CANCELLED,
  /** The prospect did not attend, with a reason recorded. */
  NO_SHOW;

  /** True while the appointment is still ahead of the reader. */
  public boolean isOpen() {
    return this == SCHEDULED || this == CONFIRMED;
  }

  /** True when the viewing reached a recorded outcome rather than staying open. */
  public boolean isClosed() {
    return this == COMPLETED || this == CANCELLED || this == NO_SHOW;
  }
}
