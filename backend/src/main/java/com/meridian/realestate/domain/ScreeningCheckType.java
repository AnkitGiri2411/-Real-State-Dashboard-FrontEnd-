package com.meridian.realestate.domain;

/**
 * The kind of check run on a rental application.
 *
 * <p>Exactly the values the {@code ck_application_screenings_type} constraint allows. The table
 * holds one row per check type per application, so this value is part of that uniqueness rather
 * than a free label.</p>
 */
public enum ScreeningCheckType {
  /** Identity of the applicant. */
  IDENTITY,
  /** Employment verification. */
  EMPLOYMENT,
  /** Income verification. */
  INCOME,
  /** A credit check. */
  CREDIT,
  /** A previous landlord's reference. */
  REFERENCE_LANDLORD,
  /** An employer's reference. */
  REFERENCE_EMPLOYER,
  /** A background check. */
  BACKGROUND,
  /** An affordability assessment. */
  AFFORDABILITY,
  /** Any other check. */
  OTHER
}
