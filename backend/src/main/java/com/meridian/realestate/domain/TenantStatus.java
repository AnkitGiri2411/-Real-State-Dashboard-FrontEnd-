package com.meridian.realestate.domain;

/**
 * The standing of a tenant.
 *
 * <p>Exactly the values the {@code ck_tenants_status} constraint allows. {@code BLACKLISTED} is
 * formally distinct from {@code INACTIVE}: a party the firm has decided not to transact with is a
 * decision, which has to be checkable before an application is accepted, rather than the silence of
 * an archived record.</p>
 */
public enum TenantStatus {
  /** In good standing. */
  ACTIVE,
  /** Not currently transacting. */
  INACTIVE,
  /** The firm has decided not to transact with this party. */
  BLACKLISTED;

  /** True when this party must be refused rather than merely not matched. */
  public boolean isBlacklisted() {
    return this == BLACKLISTED;
  }
}
