package com.meridian.realestate.domain;

/**
 * What a listing offers - to let, to sell, or both.
 *
 * <p>Exactly the values the {@code ck_listings_type} constraint allows.</p>
 */
public enum ListingType {
  /** Offered to let. Must carry an asking rent. */
  RENT,
  /** Offered for sale. Must carry an asking price. */
  SALE,
  /** Offered on either basis. Must carry at least one asking term. */
  RENT_AND_SALE
}
