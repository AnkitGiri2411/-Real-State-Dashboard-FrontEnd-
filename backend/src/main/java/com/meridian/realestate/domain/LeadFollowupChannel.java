package com.meridian.realestate.domain;

/**
 * How a planned follow-up is to be made.
 *
 * <p>Exactly the values the {@code ck_lead_followups_channel} constraint allows.</p>
 */
public enum LeadFollowupChannel {
  /** Over the phone. */
  CALL,
  /** By email. */
  EMAIL,
  /** In person. */
  MEETING,
  /** At a property or a unit. */
  SITE_VISIT,
  /** On another messaging channel. */
  MESSAGE
}
