package com.meridian.realestate.domain;

/**
 * The mandate a book of assets is held to, exactly as {@code ck_portfolios_strategy} enumerates it.
 *
 * <p>Strategy is what makes a portfolio-level yield figure interpretable: a development book is
 * not underperforming when it returns nothing, it is doing what it is for. It is a closed set
 * because comparing books managed to a different mandate is meaningless, and free text would
 * invite exactly that comparison.</p>
 */
public enum PortfolioStrategy {
  /** Stabilised, income-producing assets held for the long term. */
  CORE,
  /** Core assets with a modest value-add component. */
  CORE_PLUS,
  /** Assets held for refurbishment or re-letting to raise the return. */
  VALUE_ADD,
  /** Higher-risk positions taken for a higher expected return. */
  OPPORTUNISTIC,
  /** Assets under development, with no income to report until completion. */
  DEVELOPMENT,
  /** Assets held purely for the income they produce. */
  INCOME,
  /** Assets held for resale rather than for income. */
  TRADING;

  /** True when the book is expected to produce income, so a yield target is meaningful. */
  public boolean isIncomeProducing() {
    return this != DEVELOPMENT && this != TRADING;
  }
}
