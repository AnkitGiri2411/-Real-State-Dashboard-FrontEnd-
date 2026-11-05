package com.meridian.realestate.domain;

/**
 * What became of an offer.
 *
 * <p>Exactly the values the {@code ck_offers_status} constraint allows.</p>
 *
 * <p>{@code ACCEPTED} is the value the partial unique index {@code uq_offers_accepted_per_sale}
 * hangs on: at most one live accepted offer per sale. The index only sees {@code ACCEPTED}, so the
 * service must check the sale before accepting an offer rather than relying on the index to refuse
 * it - see {@link Offer}.</p>
 *
 * <p>{@code SUBMITTED} and {@code WITHDRAWN} are the unanswered states: an offer the buyer has
 * walked away from needs no response. The other four are answered and the schema requires a
 * response moment for each, which is what keeps "decided" and "waiting" from being conflated.</p>
 */
public enum OfferStatus {
  /** With the firm, awaiting a decision. */
  SUBMITTED,
  /** Answered with an offer in the other direction. */
  COUNTERED,
  /** Accepted - what ends the negotiation. At most one of these per sale. */
  ACCEPTED,
  /** Turned down. */
  REJECTED,
  /** Retracted by the party who made it; never needed an answer. */
  WITHDRAWN,
  /** Passed its expiry without being answered. */
  EXPIRED;

  /** True when the offer has been answered and so must carry a response moment. */
  public boolean isAnswered() {
    return this != SUBMITTED && this != WITHDRAWN;
  }
}
