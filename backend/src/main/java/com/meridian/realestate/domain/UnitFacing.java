package com.meridian.realestate.domain;

/**
 * The compass direction a unit faces, which drives light and, on the desk, price.
 *
 * <p>Exactly the values the {@code ck_units_facing} constraint allows. The column is nullable -
 * not every unit records a facing - so this enum is used only where a value is actually known,
 * never defaulted to a guess.</p>
 */
public enum UnitFacing {
  /** North. */
  N,
  /** South. */
  S,
  /** East. */
  E,
  /** West. */
  W,
  /** North-east. */
  NE,
  /** North-west. */
  NW,
  /** South-east. */
  SE,
  /** South-west. */
  SW
}
