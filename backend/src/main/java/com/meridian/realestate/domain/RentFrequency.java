package com.meridian.realestate.domain;

/**
 * How often the headline rent on a lease is billed.
 *
 * <p>Exactly the values the {@code ck_leases_frequency} constraint allows. The rent in force at any
 * date is the {@link LeaseTerm}, not this; frequency only says how a period's rent is spread across
 * the calendar.</p>
 *
 * <p>{@link #monthsPerPeriod()} is the factor that turns a figure billed at this frequency into a
 * monthly equivalent. It is provided here, once, because the rent roll and the yield figures both
 * need it and a second copy of "quarterly means three months" is exactly the kind of duplicated
 * constant that drifts.</p>
 */
public enum RentFrequency {
  /** Billed every month. */
  MONTHLY(1),
  /** Billed every three months. */
  QUARTERLY(3),
  /** Billed every six months. */
  HALF_YEARLY(6),
  /** Billed once a year. */
  ANNUAL(12);

  private final int monthsPerPeriod;

  RentFrequency(int monthsPerPeriod) {
    this.monthsPerPeriod = monthsPerPeriod;
  }

  /** The number of calendar months one billing period covers. */
  public int monthsPerPeriod() {
    return monthsPerPeriod;
  }
}
