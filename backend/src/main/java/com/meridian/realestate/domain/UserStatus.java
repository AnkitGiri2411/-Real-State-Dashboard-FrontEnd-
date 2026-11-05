package com.meridian.realestate.domain;

/**
 * The lifecycle of a staff account.
 *
 * <p>Exactly the values the {@code ck_users_status} constraint allows. Modelled as an enum rather
 * than a string so an invalid state cannot be expressed in Java at all, let alone stored - and so
 * the queue that counts staff by status has one definition of what "active" means.</p>
 *
 * <p>The distinction between the two inactive states is not cosmetic: an {@code INACTIVE} account
 * has left the firm and is kept only so the records it touched stay attributable, while a
 * {@code SUSPENDED} one is a colleague whose access is on hold and can be restored without
 * reissuing an identity.</p>
 */
public enum UserStatus {
  /** May sign in. */
  ACTIVE,
  /** Retired from the firm; the row survives so history stays attributable. */
  INACTIVE,
  /** Barred for now - a security hold or a withdrawn access - without retiring the account. */
  SUSPENDED;

  /** True when this account may open a session at all. */
  public boolean canSignIn() {
    return this == ACTIVE;
  }
}
