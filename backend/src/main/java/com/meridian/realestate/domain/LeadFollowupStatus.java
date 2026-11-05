package com.meridian.realestate.domain;

/**
 * The state of a diary entry.
 *
 * <p>Exactly the values the {@code ck_lead_followups_status} constraint allows. The constraint also
 * ties {@code completed_at} to this value - only {@code DONE} may carry it - so the overdue
 * calculation can trust that a pending row has no completion time.</p>
 */
public enum LeadFollowupStatus {
  /** Planned and not yet done. */
  PENDING,
  /** Completed, with a completion timestamp. */
  DONE,
  /** Deliberately abandoned. */
  CANCELLED,
  /** The due time passed without completion. */
  MISSED
}
