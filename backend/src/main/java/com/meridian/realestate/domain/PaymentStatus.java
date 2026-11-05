package com.meridian.realestate.domain;

/**
 * The lifecycle of a payment row.
 *
 * <p>Exactly the values the {@code ck_payments_status} constraint allows. There is deliberately no
 * {@code REFUNDED} member: a refund is a separate {@link PaymentDirection#OUTBOUND} row, because a
 * status cannot tell a partial refund from a full one and partial refunds are ordinary.</p>
 *
 * <p>Only {@link #PAID} is cash. A {@link #PENDING} receipt has not cleared, a {@link #FAILED} one
 * never will, and a {@link #VOIDED} one has been withdrawn - each has a row so the attempt is
 * recorded, but none of them counts as collected.</p>
 */
public enum PaymentStatus {
  /** Recorded but not yet cleared. */
  PENDING,
  /** Cleared. The only status that counts as collected. */
  PAID,
  /** The attempt failed, with a recorded reason. */
  FAILED,
  /** Withdrawn after the fact, with a recorded reason and actor. */
  VOIDED;

  /** True when this row represents money that actually moved. */
  public boolean isSettled() {
    return this == PAID;
  }
}
