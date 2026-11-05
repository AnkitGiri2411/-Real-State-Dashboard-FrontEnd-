package com.meridian.realestate.domain;

/**
 * The lifecycle of a unit - the smallest lettable or sellable thing in the portfolio.
 *
 * <p>Exactly the values the {@code ck_units_status} constraint allows. The letting states
 * ({@code AVAILABLE}, {@code RESERVED}, {@code OCCUPIED}) and the sale states
 * ({@code LISTED}, {@code UNDER_CONTRACT}, {@code SOLD}) share one column because a unit is being
 * let or being sold, never both at once. Modelled as an enum rather than a string so an invalid
 * state cannot be expressed in Java at all, let alone stored.</p>
 *
 * <p>{@code NOT_LETTABLE} exists because the register holds real stock - a lobby, a plant room, a
 * caretaker's flat - that is not available to let. It is a unit, but it is never part of an
 * occupancy figure; see {@code units.leasable}.</p>
 */
public enum UnitStatus {
  /** Empty and offered to let. */
  AVAILABLE,
  /** A prospective tenant has reserved it pending signature. */
  RESERVED,
  /** Let to a tenant. This is the occupancy numerator. */
  OCCUPIED,
  /** Temporarily withdrawn from the market for works. */
  UNDER_MAINTENANCE,
  /** Offered for sale. */
  LISTED,
  /** A sale has been agreed and the transaction is in progress. */
  UNDER_CONTRACT,
  /** Sold, and no longer part of the let stock. */
  SOLD,
  /** Real stock that cannot be let - a common area, plant room or marketing suite. */
  NOT_LETTABLE;

  /** True when this unit is currently producing rent. */
  public boolean isOccupied() {
    return this == OCCUPIED;
  }

  /** True when the unit still belongs in the letting pipeline rather than the sale pipeline. */
  public boolean isLettingState() {
    return this == AVAILABLE || this == RESERVED || this == OCCUPIED;
  }
}
