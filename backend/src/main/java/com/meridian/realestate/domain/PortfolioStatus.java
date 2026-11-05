package com.meridian.realestate.domain;

/**
 * Whether a portfolio is still live, exactly as {@code ck_portfolios_status} enumerates it.
 *
 * <p>Two states rather than a delete flag: closing a book is a business event with a record
 * behind it, and the constraint requires a closed portfolio to carry a closing note, so a closed
 * book cannot silently re-enter the firm's live totals.</p>
 */
public enum PortfolioStatus {
  /** Live, and included in the firm's totals. */
  ACTIVE,
  /** Closed, and excluded from the firm's live totals. */
  CLOSED;

  /** True when the book still counts towards the firm's live position. */
  public boolean isLive() {
    return this == ACTIVE;
  }
}
