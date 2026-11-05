package com.meridian.realestate.domain;

/**
 * The standing of a buyer in the sales pipeline.
 *
 * <p>Exactly the values the {@code ck_buyers_status} constraint allows. {@code CONVERTED} is a
 * lifecycle fact rather than an archive state: a buyer who has completed a purchase is still a
 * client - the one most likely to buy again - so the record is kept and marked, not deleted.</p>
 */
public enum BuyerStatus {
  /** Still looking, or between purchases. */
  ACTIVE,
  /** Not currently transacting. */
  INACTIVE,
  /** Has completed a purchase. */
  CONVERTED;

  /** True when this buyer has already bought, which is what excludes them from new-buyer reporting. */
  public boolean isConverted() {
    return this == CONVERTED;
  }
}
