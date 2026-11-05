package com.meridian.realestate.domain;

/**
 * The standing of an agent profile.
 *
 * <p>Exactly the values the {@code ck_agent_profiles_status} constraint allows. This is the
 * portfolio-carrying state, which is a different question from whether the staff account may sign
 * in: an agent who has left the desk stops receiving stock even while their account is being
 * closed.</p>
 */
public enum AgentStatus {
  /** Carrying a portfolio. */
  ACTIVE,
  /** Not carrying stock - on leave, moved desks, or departed. */
  INACTIVE;
}
