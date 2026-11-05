package com.meridian.realestate.domain;

/**
 * What kind of party a tenant is.
 *
 * <p>Exactly the values the {@code ck_tenants_type} constraint allows. A company tenant carries a
 * {@code registration_number} and is screened on its filings; an individual carries an occupation,
 * an employer and a declared income. The type is what tells an application screening which of
 * those the record is expected to hold.</p>
 */
public enum TenantType {
  /** A natural person. */
  INDIVIDUAL,
  /** A registered company. */
  COMPANY,
  /** A trust, occupying through its trustees. */
  TRUST,
  /** A government body. */
  GOVERNMENT,
  /** Anything the list above does not cover. */
  OTHER;
}
