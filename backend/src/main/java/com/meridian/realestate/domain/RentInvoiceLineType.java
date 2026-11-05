package com.meridian.realestate.domain;

/**
 * What a line of a rent invoice charges for.
 *
 * <p>Exactly the values the {@code ck_rent_invoice_lines_type} constraint allows. The distinction
 * matters because only {@link #RENT} is the rent itself; a maintenance passthrough or a parking
 * charge is billed on the same document but is not rental income, and a report that cannot tell
 * them apart overstates the portfolio's rent.</p>
 */
public enum RentInvoiceLineType {
  /** The rent for the period. */
  RENT,
  /** A maintenance charge passed on to the tenant. */
  MAINTENANCE,
  /** Utilities recovered from the tenant. */
  UTILITY,
  /** Parking or similar ancillary charge. */
  PARKING,
  /** A charge for late payment. */
  LATE_FEE,
  /** A goodwill credit or other adjustment - legitimately negative. */
  ADJUSTMENT,
  /** A tax line. */
  TAX,
  /** Anything else. */
  OTHER
}
