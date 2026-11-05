package com.meridian.realestate.domain;

/**
 * What kind of party an owner is.
 *
 * <p>Exactly the values the {@code ck_owners_type} constraint allows. The distinction is not
 * decoration: a trust holds through its trustees, whose names belong in the owner's
 * {@code primary_contact_name} rather than in this column, and a joint venture is reported as a
 * vehicle rather than as a person. Modelled as an enum so a value the database would reject cannot
 * be expressed in Java at all.</p>
 */
public enum OwnerType {
  /** A natural person. */
  INDIVIDUAL,
  /** A registered company. */
  COMPANY,
  /** A partnership firm. */
  PARTNERSHIP,
  /** A trust, holding through its trustees. */
  TRUST,
  /** A joint-venture vehicle holding on behalf of its partners. */
  JOINT_VENTURE,
  /** A government body. */
  GOVERNMENT,
  /** Anything the list above does not cover. */
  OTHER;
}
