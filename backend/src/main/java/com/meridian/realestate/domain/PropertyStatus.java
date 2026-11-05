package com.meridian.realestate.domain;

/**
 * The lifecycle of an asset in the register.
 *
 * <p>Exactly the values the {@code ck_properties_status} constraint allows. Modelled as an enum
 * rather than a string so an invalid state cannot be expressed in Java at all, let alone stored.</p>
 */
public enum PropertyStatus {
  /** On the books but not yet offered anywhere. */
  DRAFT,
  /** Actively offered - either to let or for sale. */
  LISTED,
  /** Available and being marketed without a formal listing record. */
  AVAILABLE_ACTIVE,
  /** An offer has been accepted and the transaction is in progress. */
  UNDER_CONTRACT,
  /** Let to a tenant. */
  LEASED,
  /** Sold. */
  SOLD,
  /** Withdrawn from the market but still owned. */
  INACTIVE;

  /** True when the asset is no longer available to let or to sell. */
  public boolean isTerminal() {
    return this == SOLD;
  }
}
