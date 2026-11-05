package com.meridian.realestate.domain;

/**
 * Where a lead sits in the pipeline.
 *
 * <p>Exactly the values the {@code ck_leads_stage} constraint allows. The permitted transitions
 * between them live in {@code LeadLifecycle} and are refused server-side - a stage is a state the
 * machine knows how to move through, not a free label.</p>
 */
public enum LeadStage {
  /** Just captured, not yet worked. */
  NEW,
  /** First contact has been made. */
  CONTACTED,
  /** Fit and interest have been established. */
  QUALIFIED,
  /** A viewing of stock has been arranged or held. */
  VIEWING,
  /** Terms are under discussion. */
  NEGOTIATION,
  /** Converted - a tenancy or a purchase. */
  WON,
  /** Did not convert. */
  LOST;

  /** True while the lead is still being worked, which is what the pipeline value sums. */
  public boolean isOpen() {
    return this != WON && this != LOST;
  }
}
