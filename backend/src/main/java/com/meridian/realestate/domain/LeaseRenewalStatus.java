package com.meridian.realestate.domain;

/**
 * The state of the renewal conversation for a lease.
 *
 * <p>Exactly the values the {@code ck_lease_renewals_status} constraint allows. A renewal offer
 * exists before the new lease does, and its outcome is what tells the firm whether a tenancy is
 * continuing - the single most valuable thing to know about a lease that is about to end.</p>
 *
 * <p>{@link #PENDING} and {@link #OFFERED} are the <em>open</em> states: the pair the partial unique
 * index {@code uq_lease_renewals_open} counts when it enforces one live renewal conversation per
 * lease. {@link #isOpen()} names that pair once.</p>
 */
public enum LeaseRenewalStatus {
  /** The renewal is being considered; nothing has been put to the tenant yet. */
  PENDING,
  /** An offer has been made and is awaiting the tenant's answer. */
  OFFERED,
  /** Agreed; the new lease exists and is named by {@code renewed_lease_id}. */
  ACCEPTED,
  /** The tenant declined, with a recorded decision moment. */
  DECLINED,
  /** The offer was never answered and has lapsed. */
  LAPSED;

  /** True for the two states that count as an open renewal in {@code uq_lease_renewals_open}. */
  public boolean isOpen() {
    return this == PENDING || this == OFFERED;
  }
}
