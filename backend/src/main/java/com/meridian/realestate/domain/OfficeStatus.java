package com.meridian.realestate.domain;

/**
 * The standing of an office.
 *
 * <p>Exactly the values the {@code ck_offices_status} constraint allows. A closed branch is
 * deactivated rather than removed, because the agents, leads and properties that were attributed to
 * it must keep reporting under the office they actually belonged to.</p>
 */
public enum OfficeStatus {
  /** Operating. */
  ACTIVE,
  /** Closed or not currently operating. */
  INACTIVE;
}
