package com.meridian.realestate.domain;

/**
 * The legal shape of the party applying for a unit.
 *
 * <p>Exactly the values the {@code ck_rental_applications_applicant_type} constraint allows.</p>
 */
public enum ApplicantType {
  /** A private individual. */
  INDIVIDUAL,
  /** A company. */
  COMPANY,
  /** A trust. */
  TRUST,
  /** A government body. */
  GOVERNMENT,
  /** Any other legal shape. */
  OTHER
}
