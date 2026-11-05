package com.meridian.realestate.domain;

/**
 * How a unit is furnished, which is what a letting enquiry is usually filtered by.
 *
 * <p>Exactly the values the {@code ck_units_furnishing} constraint allows. Modelled as an enum so
 * the three states the desk talks in - bare shell, partly fitted, fully fitted - are the only
 * three that can be stored.</p>
 */
public enum UnitFurnishing {
  /** Bare shell: no furniture is provided. */
  UNFURNISHED,
  /** Partly fitted - typically wardrobes, kitchen and air-conditioning. */
  SEMI_FURNISHED,
  /** Fully fitted and ready to occupy. */
  FURNISHED
}
