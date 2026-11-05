package com.meridian.realestate.domain;

/**
 * How urgently a lead should be worked.
 *
 * <p>Exactly the values the {@code ck_leads_priority} constraint allows.</p>
 */
public enum LeadPriority {
  /** No particular urgency. */
  LOW,
  /** The default. */
  MEDIUM,
  /** Should be worked ahead of the routine queue. */
  HIGH,
  /** Requires immediate attention. */
  URGENT
}
