package com.meridian.realestate.domain;

/**
 * What kind of party a buyer is.
 *
 * <p>Exactly the values the {@code ck_buyers_type} constraint allows. {@code NRI} and {@code FUND}
 * are separate from {@code INDIVIDUAL} and {@code COMPANY} because a non-resident buyer and an
 * institutional buyer are subject to different paperwork on a sale, and the pipeline reports them
 * apart.</p>
 */
public enum BuyerType {
  /** A natural person resident in the country. */
  INDIVIDUAL,
  /** A registered company. */
  COMPANY,
  /** A non-resident individual. */
  NRI,
  /** A trust, buying through its trustees. */
  TRUST,
  /** An institutional fund. */
  FUND,
  /** A joint-venture vehicle buying on behalf of its partners. */
  JOINT_VENTURE,
  /** Anything the list above does not cover. */
  OTHER;
}
