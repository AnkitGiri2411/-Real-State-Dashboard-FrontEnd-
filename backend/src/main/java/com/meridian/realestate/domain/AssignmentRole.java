package com.meridian.realestate.domain;

/**
 * The capacity in which an agent carries a property.
 *
 * <p>Exactly the values the {@code ck_agent_property_assignments_role} constraint allows.
 * {@code PRIMARY} is the load-bearing value: the schema allows at most one open primary row per
 * property, enforced by the partial unique index {@code uq_agent_property_primary_open}, because
 * "the property's agent" has to have a single answer before any report can attribute the property
 * to a person.</p>
 */
public enum AssignmentRole {
  /** The agent answerable for the property. */
  PRIMARY,
  /** Assists on the property. */
  SECONDARY,
  /** An agent from another office sharing the instruction. */
  CO_AGENT;
}
