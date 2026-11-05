package com.meridian.realestate.domain;

/**
 * The kind of work recorded against a lead.
 *
 * <p>Exactly the values the {@code ck_lead_activities_type} constraint allows.</p>
 */
public enum LeadActivityType {
  /** A phone call. */
  CALL,
  /** An email exchange. */
  EMAIL,
  /** A meeting. */
  MEETING,
  /** A free-text note. */
  NOTE,
  /** A visit to a property or a unit. */
  SITE_VISIT,
  /** A message on another channel. */
  MESSAGE,
  /** The record of a stage move, so the timeline shows why a lead advanced. */
  STAGE_CHANGE,
  /** A system-generated entry. */
  SYSTEM
}
