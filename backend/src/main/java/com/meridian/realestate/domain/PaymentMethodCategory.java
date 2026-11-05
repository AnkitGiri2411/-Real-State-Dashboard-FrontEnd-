package com.meridian.realestate.domain;

/**
 * The family a payment method belongs to.
 *
 * <p>Exactly the values the {@code ck_payment_methods_category} constraint allows. It is coarser
 * than the method's {@code code}: {@code NEFT} and {@code IMPS} are two codes but one category,
 * {@code BANK_TRANSFER}. Reporting groups by category and reconciles by code.</p>
 */
public enum PaymentMethodCategory {
  /** Physical cash. */
  CASH,
  /** A cheque. */
  CHEQUE,
  /** A bank transfer - NEFT, RTGS or IMPS. */
  BANK_TRANSFER,
  /** A UPI transfer. */
  UPI,
  /** A card payment. */
  CARD,
  /** A standing instruction or mandate. */
  AUTO_DEBIT,
  /** A demand draft. */
  DEMAND_DRAFT,
  /** Anything else. */
  OTHER
}
