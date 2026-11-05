package com.meridian.realestate.domain;

/**
 * How the applicant earns, which shapes the affordability decision.
 *
 * <p>Exactly the values the {@code ck_rental_applications_employment} constraint allows. The column
 * is nullable, so the value is optional.</p>
 */
public enum EmploymentType {
  /** In paid employment. */
  SALARIED,
  /** Self-employed. */
  SELF_EMPLOYED,
  /** Owns and runs a business. */
  BUSINESS_OWNER,
  /** In full-time education. */
  STUDENT,
  /** Retired. */
  RETIRED,
  /** Any other basis. */
  OTHER
}
