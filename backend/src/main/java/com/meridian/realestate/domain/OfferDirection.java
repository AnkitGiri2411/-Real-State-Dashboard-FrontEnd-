package com.meridian.realestate.domain;

/**
 * Who put an offer forward.
 *
 * <p>Exactly the values the {@code ck_offers_direction} constraint allows.</p>
 *
 * <p>Both directions are real and the distinction is the substance of a negotiation: a run of
 * {@code FIRM} entries after a {@code BUYER} entry is the firm countering, and the sequence is what
 * shows whether a deal is moving or stuck. A single "current offer" amount cannot express that, which
 * is why the offer table keeps every step rather than one number.</p>
 */
public enum OfferDirection {
  /** Put forward by the buyer. */
  BUYER,
  /** Put forward by the firm as a counter. */
  FIRM
}
