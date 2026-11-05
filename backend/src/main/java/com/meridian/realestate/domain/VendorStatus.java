package com.meridian.realestate.domain;

/**
 * Whether a vendor may be given work.
 *
 * <p>Exactly the values the {@code ck_vendors_status} constraint allows. Modelled as an enum rather
 * than a string so a state the database would reject cannot be expressed in Java at all.</p>
 *
 * <p>{@code SUSPENDED} is deliberately separate from {@code INACTIVE}. An inactive vendor is simply
 * one the firm no longer works with; a suspended vendor was stopped, usually for cause, and a
 * suspended vendor must not be selectable for new work while remaining readable on the history of
 * the work it already did.</p>
 */
public enum VendorStatus {
  /** Available for assignment. */
  ACTIVE,
  /** No longer engaged, but not stopped for cause. */
  INACTIVE,
  /** Stopped - must not be assigned new work. */
  SUSPENDED;

  /** True when the vendor may be given new work. */
  public boolean isAssignable() {
    return this == ACTIVE;
  }
}
