package com.meridian.realestate.domain;

/**
 * The stored lifecycle of a rent invoice.
 *
 * <p>Exactly the values the {@code ck_rent_invoices_status} constraint allows. Note what is
 * <b>not</b> here: there is deliberately no {@code PAID}, {@code PARTIALLY_PAID} or {@code OVERDUE}.
 * Those are functions of the allocations posted against the invoice, and storing one would create a
 * value that can silently disagree with the ledger it summarises - the classic way a system reports
 * an invoice as settled while the money is missing.</p>
 *
 * <p>What is stored is only the decision somebody took: it is a {@link #DRAFT}, it has been
 * {@link #ISSUED}, it has been {@link #VOID}ed, or it has been {@link #WRITTEN_OFF}. Settlement is
 * derived in the service layer from {@code payment_allocations}.</p>
 */
public enum InvoiceStatus {
  /** Being built; not yet a demand for money. */
  DRAFT,
  /** Issued to the tenant. The only state that is collectable. */
  ISSUED,
  /** Cancelled after issue. Its allocations no longer count toward settlement. */
  VOID,
  /** Abandoned as uncollectable, with a recorded reason and actor. */
  WRITTEN_OFF;

  /** True for the one state from which money can still be collected. */
  public boolean isCollectable() {
    return this == ISSUED;
  }
}
