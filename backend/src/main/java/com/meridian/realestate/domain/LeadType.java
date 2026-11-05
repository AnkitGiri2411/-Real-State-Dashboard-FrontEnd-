package com.meridian.realestate.domain;

/**
 * What a lead is looking for - to rent, to buy, or both.
 *
 * <p>Exactly the values the {@code ck_leads_type} constraint allows.</p>
 */
public enum LeadType {
  /** Looking for a tenancy. */
  RENTAL,
  /** Looking to purchase. */
  SALE,
  /** Open to either. */
  BOTH
}
