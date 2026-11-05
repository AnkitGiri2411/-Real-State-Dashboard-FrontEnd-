package com.meridian.realestate.domain;

/**
 * Where a sale stands in the pipeline.
 *
 * <p>Exactly the values the {@code ck_sales_opportunities_stage} constraint allows, in the order the
 * pipeline runs: {@code LEAD} &rarr; {@code OFFER} &rarr; {@code NEGOTIATION} &rarr;
 * {@code UNDER_CONTRACT} &rarr; {@code CLOSED}, with {@code LOST} reachable from any point before
 * contract.</p>
 *
 * <p>Two stages are concluded: {@code CLOSED} and {@code LOST}. That pair is the set the schema's
 * partial index {@code ix_sales_opportunities_pipeline} excludes and the set every open-pipeline
 * figure and every funnel excludes - see {@link #isConcluded()}. Including a concluded deal in the
 * pipeline would double-count it against the sale it became.</p>
 *
 * <p>The stage also drives the outcome columns: {@link SalesOpportunity} carries the CHECK that only
 * a {@code CLOSED} row has an agreed price and only a {@code LOST} row has a loss reason, which is
 * what stops a concluded deal being read as a live one.</p>
 */
public enum SaleStage {
  /** An enquiry recorded as a possible sale; nothing priced or offered yet. */
  LEAD,
  /** An offer has been put forward and is being considered. */
  OFFER,
  /** Buyer and firm are trading offers. */
  NEGOTIATION,
  /** Terms agreed; contracts are being executed. */
  UNDER_CONTRACT,
  /** Concluded successfully - the schema requires an agreed price and a closure moment. */
  CLOSED,
  /** Concluded unsuccessfully - the schema requires a loss moment and a reason. */
  LOST;

  /** True when the deal has concluded, either way, and is no longer part of the pipeline. */
  public boolean isConcluded() {
    return this == CLOSED || this == LOST;
  }
}
