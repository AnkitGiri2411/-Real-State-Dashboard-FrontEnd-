package com.meridian.realestate.domain;

/**
 * Which way the money moved.
 *
 * <p>Exactly the values the {@code ck_payments_direction} constraint allows. {@link #INBOUND} is
 * money received; {@link #OUTBOUND} is money paid back - a refund - and an outbound row always names
 * the receipt it reverses through {@code refund_of_payment_id}. A refund is its own row rather than
 * a status so that a <em>partial</em> refund is representable, which a status cannot express.</p>
 *
 * <p>{@code payments.amount} is always positive, so "collected" is not a naive {@code SUM(amount)}:
 * it is a signed sum over rows, which is what {@link #sign()} exists to make explicit.</p>
 */
public enum PaymentDirection {
  /** Money in: a receipt. */
  INBOUND,
  /** Money back out: a refund against an earlier receipt. */
  OUTBOUND;

  /** {@code +1} for a receipt, {@code -1} for a refund; the factor that signs an amount. */
  public int sign() {
    return this == INBOUND ? 1 : -1;
  }
}
