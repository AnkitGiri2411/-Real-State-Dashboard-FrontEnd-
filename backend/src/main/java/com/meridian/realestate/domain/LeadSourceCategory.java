package com.meridian.realestate.domain;

/**
 * The kind of channel a lead source belongs to.
 *
 * <p>Exactly the values the {@code ck_lead_sources_category} constraint allows. Modelled as an enum
 * rather than a string so an invalid category cannot be expressed in Java at all, let alone
 * stored.</p>
 */
public enum LeadSourceCategory {
  /** A property portal - 99acres, MagicBricks and the like. */
  PORTAL,
  /** The firm's own digital estate - website, paid landing pages. */
  DIGITAL,
  /** Introduced by a person - a client, a resident. */
  REFERRAL,
  /** An enquiry at the office or on a site. */
  WALK_IN,
  /** Introduced by a channel partner. */
  BROKER,
  /** The result of a marketing campaign. */
  CAMPAIGN,
  /** From a board on a building. */
  SIGNAGE,
  /** A direct approach from a company. */
  CORPORATE,
  /** Not attributable to a known category. */
  OTHER
}
