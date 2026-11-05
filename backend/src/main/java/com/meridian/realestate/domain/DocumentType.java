package com.meridian.realestate.domain;

/**
 * What a document is, exactly as {@code ck_documents_type} enumerates it.
 *
 * <p>The register is read by type - "every insurance policy", "every NOC expiring this quarter" -
 * so the type is a closed set rather than free text. A misspelled free-text type would fall
 * outside the filter that exists to find it, silently, which is the failure mode the constraint
 * and this enum remove together.</p>
 */
public enum DocumentType {
  /** The executed tenancy agreement. */
  LEASE_AGREEMENT,
  /** The deed recording a completed sale. */
  SALE_DEED,
  /** Evidence of title to the asset. */
  TITLE_DEED,
  /** The agreement to sell, which precedes the deed. */
  AGREEMENT_TO_SELL,
  /** A no-objection certificate from an authority or a society. */
  NOC,
  /** A tax invoice held as the record of a charge. */
  TAX_INVOICE,
  /** The insurance policy covering the asset. */
  INSURANCE_POLICY,
  /** A certificate evidencing statutory compliance. */
  COMPLIANCE_CERTIFICATE,
  /** Identity proof held for a counterparty. */
  ID_PROOF,
  /** The internal floor plan. */
  FLOOR_PLAN,
  /** The site plan. */
  SITE_PLAN,
  /** A valuation report supporting the recorded valuation. */
  VALUATION_REPORT,
  /** The note recording handover of a completed unit. */
  HANDOVER_NOTE,
  /** The letter recording possession. */
  POSSESSION_LETTER,
  /** A utility bill held as evidence of an address or a charge. */
  UTILITY_BILL,
  /** Anything that genuinely does not fit the list above. */
  OTHER
}
