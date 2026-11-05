package com.meridian.realestate.domain;

/**
 * The publication state of a listing.
 *
 * <p>Exactly the values the {@code ck_listings_status} constraint allows. Modelled as an enum
 * rather than a string so an invalid state cannot be expressed in Java at all, let alone
 * stored.</p>
 *
 * <p>The constraint also ties {@code closed_at} to the state - the closed states must carry it and
 * the others must not - so the "stale listing" calculation can trust the timestamp.</p>
 */
public enum ListingStatus {
  /** Prepared but not yet published. */
  DRAFT,
  /** Published but not yet live on the board. */
  PUBLISHED,
  /** Live on the market. */
  ACTIVE,
  /** An offer is in hand. */
  UNDER_OFFER,
  /** Let agreed; the listing is closed. */
  LET_AGREED,
  /** Sold; the listing is closed. */
  SOLD,
  /** Withdrawn from the market; the listing is closed. */
  WITHDRAWN,
  /** Ran past its end date; the listing is closed. */
  EXPIRED;

  /** True when the listing is no longer doing anything and {@code closed_at} must be set. */
  public boolean isClosed() {
    return this == LET_AGREED || this == SOLD || this == WITHDRAWN || this == EXPIRED;
  }

  /** True when the listing is still live on the board. */
  public boolean isLive() {
    return this == PUBLISHED || this == ACTIVE || this == UNDER_OFFER;
  }
}
