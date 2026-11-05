package com.meridian.realestate.domain;

/**
 * What a buyer intends to do with the property they are looking for.
 *
 * <p>Exactly the values the {@code ck_buyer_requirements_purpose} constraint allows. Purpose is
 * what makes matching honest: the same flat is a poor match for an investor who needs a yield and a
 * good one for a household who needs to live in it, and a letting brief must be matched against
 * stock that may actually be let.</p>
 */
public enum BuyerRequirementPurpose {
  /** The buyer intends to live in the property. */
  OWN_OCCUPANCY,
  /** Bought to hold for capital appreciation or yield. */
  INVESTMENT,
  /** Bought in order to let it out. */
  LETTING,
  /** Bought to redevelop. */
  REDEVELOPMENT;
}
