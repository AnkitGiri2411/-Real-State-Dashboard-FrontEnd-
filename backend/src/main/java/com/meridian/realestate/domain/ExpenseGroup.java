package com.meridian.realestate.domain;

/**
 * How an expense category is grouped for reporting.
 *
 * <p>Exactly the values the {@code ck_expense_categories_group} constraint allows.</p>
 *
 * <p>The group is a reporting bucket, not the operating-versus-capital distinction - that is
 * carried separately by {@code expense_categories.is_capital} (see {@link ExpenseCategory}). The
 * two are not redundant: {@code REPAIR} work is operating spend and {@code CAPITAL} work is not,
 * but the income-versus-expense chart still wants repairs shown under their own heading. A group
 * that was made to stand in for the capital flag would have to collapse those headings away.</p>
 */
public enum ExpenseGroup {
  OPERATING,
  REPAIR,
  UTILITY,
  TAX,
  INSURANCE,
  MANAGEMENT,
  MARKETING,
  CAPITAL,
  OTHER
}
