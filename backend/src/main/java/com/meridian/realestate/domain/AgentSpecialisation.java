package com.meridian.realestate.domain;

/**
 * What an agent carries.
 *
 * <p>Exactly the values the {@code ck_agent_profiles_specialisation} constraint allows. The
 * specialisation is what a lead is routed by, so a lettings-only agent is never handed a sale, and
 * the performance report compares an agent against the target for the desk they actually work.</p>
 */
public enum AgentSpecialisation {
  /** Works the lettings desk. */
  LETTING,
  /** Works the sales desk. */
  SALES,
  /** Works both desks. */
  BOTH,
  /** Manages stock on behalf of owners rather than marketing it. */
  PROPERTY_MANAGEMENT,
  /** Prepares valuations. */
  VALUATION;

  /** True when this agent can be given a letting - the condition for routing a rental lead. */
  public boolean coversLetting() {
    return this == LETTING || this == BOTH || this == PROPERTY_MANAGEMENT;
  }

  /** True when this agent can be given a sale - the condition for routing a sales lead. */
  public boolean coversSales() {
    return this == SALES || this == BOTH || this == VALUATION;
  }
}
