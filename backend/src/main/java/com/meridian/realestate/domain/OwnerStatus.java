package com.meridian.realestate.domain;

/**
 * Whether the firm still deals with an owner.
 *
 * <p>Exactly the values the {@code ck_owners_status} constraint allows. There is deliberately no
 * blacklisted state here: an owner is not a counterparty the firm screens - it is the client whose
 * asset is being managed - so the decisive states are simply "on the books" and "not".</p>
 */
public enum OwnerStatus {
  /** On the books and reportable. */
  ACTIVE,
  /** No longer transacted with; the record survives for history. */
  INACTIVE;
}
