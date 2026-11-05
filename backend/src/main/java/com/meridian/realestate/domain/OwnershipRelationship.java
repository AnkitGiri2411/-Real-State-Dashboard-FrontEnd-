package com.meridian.realestate.domain;

/**
 * The capacity in which an owner holds a share of a property.
 *
 * <p>Exactly the values the {@code ck_property_ownerships_relationship} constraint allows. The
 * wording matters to owner reporting: a development partner's share is not the same claim as a
 * beneficiary's, and a lessor of the land is reported separately from the owner of what stands on
 * it, even though both hold a percentage of the same title.</p>
 */
public enum OwnershipRelationship {
  /** The property is held by this owner alone. */
  SOLE_OWNER,
  /** Held jointly with one or more other owners. */
  CO_OWNER,
  /** Holds the larger part of the property. */
  MAJORITY_HOLDER,
  /** Holds the smaller part of the property. */
  MINORITY_HOLDER,
  /** Holds an interest for the benefit of another party. */
  BENEFICIARY,
  /** A partner in the development of the property rather than a passive holder. */
  DEVELOPMENT_PARTNER,
  /** Leases the land out and holds the ground interest. */
  LESSOR,
  /** Anything the list above does not cover. */
  OTHER;
}
