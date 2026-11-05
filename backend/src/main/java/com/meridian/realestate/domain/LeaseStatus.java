package com.meridian.realestate.domain;

/**
 * The lifecycle of a lease.
 *
 * <p>Exactly the values the {@code ck_leases_status} constraint allows, in the order a tenancy
 * moves through them. Modelled as an enum rather than a string so an invalid state cannot be
 * expressed in Java at all, let alone stored.</p>
 *
 * <p>Only {@link #ACTIVE} and {@link #EXPIRING} are <em>running</em>: the two states the partial
 * unique index {@code uq_leases_one_running_per_unit} counts when it enforces that a unit cannot be
 * let to two tenants at once. {@link #isRunning()} exists so that pair is written once rather than
 * re-listed by every caller that needs "the lease that is currently in force".</p>
 */
public enum LeaseStatus {
  /** Drawn up but not yet offered for signature. */
  DRAFT,
  /** With the tenant, awaiting signature. */
  PENDING,
  /** Signed and in force - the only state a running tenancy is normally in. */
  ACTIVE,
  /** In force but inside the configured expiry window, so it needs a decision. */
  EXPIRING,
  /** Superseded by a renewal lease that points back at it through {@code renewal_of_lease_id}. */
  RENEWED,
  /** Ended by notice, expiry or agreement, with a recorded reason. */
  TERMINATED,
  /** Abandoned before it ever came into force. */
  CANCELLED;

  /** True for the two states that count as "running" in {@code uq_leases_one_running_per_unit}. */
  public boolean isRunning() {
    return this == ACTIVE || this == EXPIRING;
  }
}
