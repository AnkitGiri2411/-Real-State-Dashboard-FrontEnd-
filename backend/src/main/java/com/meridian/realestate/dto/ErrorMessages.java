package com.meridian.realestate.dto;

import java.util.Map;

/**
 * The one place an internal error code becomes a sentence a person can act on.
 *
 * <p>A code like {@code DUPLICATE_UNIT_CODE} is what the frontend branches on and what the log
 * carries; it is never what the desk reads. Keeping the translation here means one edit fixes
 * the wording everywhere, and it stops a controller from inventing its own phrasing for a
 * failure the service layer already named.</p>
 *
 * <p>An unknown code falls back to a neutral sentence rather than surfacing the raw code, so a
 * code added in the service layer can never reach a user as shouting capitals.</p>
 */
public final class ErrorMessages {

  private ErrorMessages() {}

  /**
   * The one sentence an unrecognised code falls back to.
   *
   * <p>A single constant rather than a literal repeated at each call site, so the fallback is also
   * testable and can be changed in one place.</p>
   */
  private static final String FALLBACK = "Something went wrong at our end. Please try again";

  private static final Map<String, String> FRIENDLY =
      Map.ofEntries(
          // ---- identity, session, account ----
          Map.entry("INVALID_CREDENTIALS", "That e-mail address and password do not match"),
          Map.entry("UNAUTHORIZED", "Please sign in to continue"),
          Map.entry("FORBIDDEN", "Your account does not have permission to do that"),
          Map.entry("ACCOUNT_INACTIVE", "This account is not active"),
          Map.entry("ACCOUNT_LOCKED", "This account is locked"),
          Map.entry("REFRESH_TOKEN_REVOKED", "Your session has ended. Please sign in again"),
          Map.entry("REFRESH_TOKEN_EXPIRED", "Your session has expired. Please sign in again"),
          Map.entry("WEAK_PASSWORD", "Password must be at least 8 characters"),
          Map.entry("PASSWORD_MISMATCH", "The passwords do not match"),
          Map.entry("EMAIL_EXISTS", "An account with this e-mail address already exists"),
          Map.entry("CANNOT_DEACTIVATE_LAST_OWNER", "The last active owner cannot be deactivated"),
          Map.entry("CANNOT_REMOVE_LAST_OWNER", "The last active owner role cannot be removed"),

          // ---- general ----
          Map.entry("NOT_FOUND", "We could not find that record"),
          Map.entry("VALIDATION_FAILED", "Some of the details need correcting"),
          Map.entry("MALFORMED_BODY", "The request body was missing or could not be read"),
          Map.entry("INVALID_PATH_PARAMETER", "The request address contains an invalid value"),
          Map.entry("MISSING_PARAMETER", "A required request parameter is missing"),
          Map.entry("METHOD_NOT_ALLOWED", "That action is not supported on this address"),
          Map.entry("UNSUPPORTED_CONTENT_TYPE", "The request was not sent in a supported format"),
          // INTERNAL_ERROR deliberately has no entry of its own: its wording IS the fallback, so a
          // duplicate entry would only give the map two places to disagree with itself.
          // CONSTRAINT_VIOLATION is what GlobalExceptionHandler answers when the service layer let a
          // unique or foreign-key constraint through - the test caught that it was never mapped.
          Map.entry("CONSTRAINT_VIOLATION",
              "That change conflicts with other records in the portfolio"),

          // ---- properties, buildings, units ----
          Map.entry("PROPERTY_NOT_FOUND", "We could not find that property"),
          Map.entry("BUILDING_NOT_FOUND", "We could not find that building"),
          Map.entry("UNIT_NOT_FOUND", "We could not find that unit"),
          Map.entry("DUPLICATE_PROPERTY_CODE", "A property with this code already exists"),
          Map.entry("DUPLICATE_UNIT_CODE", "A unit with this code already exists"),
          Map.entry("UNIT_NOT_LEASABLE", "This unit is not available to let"),
          Map.entry("UNIT_NOT_AVAILABLE", "This unit is not currently available"),
          Map.entry("PROPERTY_HAS_ACTIVE_LEASES",
              "This property still has running leases and cannot be archived"),
          Map.entry("BUILDING_HAS_UNITS",
              "This building still has units and cannot be removed"),

          // ---- ownership ----
          Map.entry("OWNER_NOT_FOUND", "We could not find that owner"),
          Map.entry("DUPLICATE_OWNER_CODE", "An owner with this code already exists"),
          Map.entry("OWNERSHIP_EXCEEDS_ONE_HUNDRED",
              "Ownership shares for a property cannot add up to more than 100%"),
          Map.entry("OWNERSHIP_NOT_ONE_HUNDRED",
              "The ownership shares for this property must add up to exactly 100%"),
          Map.entry("OWNERSHIP_DUPLICATE_OWNER",
              "That owner already holds a share of this property for the same period"),

          // ---- tenants, buyers ----
          Map.entry("TENANT_NOT_FOUND", "We could not find that tenant"),
          Map.entry("BUYER_NOT_FOUND", "We could not find that buyer"),
          Map.entry("DUPLICATE_TENANT_CODE", "A tenant with this code already exists"),
          Map.entry("DUPLICATE_BUYER_CODE", "A buyer with this code already exists"),
          Map.entry("DUPLICATE_TENANT_EMAIL", "A tenant with this e-mail address already exists"),
          Map.entry("DUPLICATE_TENANT_PHONE", "A tenant with this phone number already exists"),
          Map.entry("TENANT_HAS_RUNNING_LEASE",
              "This tenant has a running lease and cannot be archived"),

          // ---- agents, offices ----
          Map.entry("AGENT_NOT_FOUND", "We could not find that agent"),
          Map.entry("OFFICE_NOT_FOUND", "We could not find that office"),
          Map.entry("DUPLICATE_OFFICE_CODE", "An office with this code already exists"),
          Map.entry("AGENT_USER_ALREADY_LINKED",
              "That member of staff already has an agent profile"),
          Map.entry("INVALID_MANAGER_ID", "That manager selection is not valid"),

          // ---- leads ----
          Map.entry("LEAD_NOT_FOUND", "We could not find that lead"),
          Map.entry("DUPLICATE_LEAD_CODE", "A lead with this code already exists"),
          Map.entry("LEAD_ALREADY_CONVERTED", "This lead has already been converted"),
          Map.entry("LEAD_TRANSITION_NOT_ALLOWED",
              "The lead cannot move to that stage from where it stands"),
          Map.entry("LEAD_ASSIGN_INACTIVE_AGENT",
              "That agent is not active and cannot be assigned work"),

          // ---- listings, viewings ----
          Map.entry("LISTING_NOT_FOUND", "We could not find that listing"),
          Map.entry("DUPLICATE_LISTING_CODE", "A listing with this code already exists"),
          Map.entry("LISTING_ALREADY_ACTIVE", "This property or unit is already listed"),
          Map.entry("VIEWING_NOT_FOUND", "We could not find that viewing"),
          Map.entry("VIEWING_NOT_COMPLETABLE", "Only a scheduled or confirmed viewing can be completed"),
          Map.entry("VIEWING_ALREADY_CANCELLED", "This viewing has already been cancelled"),

          // ---- applications ----
          Map.entry("APPLICATION_NOT_FOUND", "We could not find that application"),
          Map.entry("DUPLICATE_APPLICATION_CODE", "An application with this code already exists"),
          Map.entry("APPLICATION_ALREADY_OPEN_ON_UNIT",
              "This unit already has an open application from another applicant"),
          Map.entry("APPLICATION_NOT_DECIDABLE",
              "This application has already been decided"),
          Map.entry("SCREENING_INCOMPLETE",
              "Every screening check must be finished before a decision can be taken"),

          // ---- leases ----
          Map.entry("LEASE_NOT_FOUND", "We could not find that lease"),
          Map.entry("DUPLICATE_LEASE_CODE", "A lease with this code already exists"),
          Map.entry("UNIT_ALREADY_LET",
              "This unit already has a running lease and cannot be let twice"),
          Map.entry("LEASE_TRANSITION_NOT_ALLOWED",
              "The lease cannot move to that status from where it stands"),
          Map.entry("LEASE_TERM_OVERLAP", "A rent revision already covers part of that period"),
          Map.entry("LEASE_END_BEFORE_START", "The lease end date cannot fall before its start date"),
          Map.entry("LEASE_RENEWAL_NOT_OFFERED",
              "A renewal can only be offered on a running or expiring lease"),

          // ---- rent, invoices, payments ----
          Map.entry("INVOICE_NOT_FOUND", "We could not find that invoice"),
          Map.entry("DUPLICATE_INVOICE_NUMBER", "An invoice with this number already exists"),
          Map.entry("INVOICE_ALREADY_ISSUED", "This invoice has already been issued"),
          Map.entry("INVOICE_ALREADY_VOID", "This invoice has been voided"),
          Map.entry("INVOICE_PERIOD_ALREADY_INVOICED",
              "A rent invoice already covers this period for this lease"),
          Map.entry("INVOICE_LINES_DO_NOT_RECONCILE",
              "The invoice lines do not add up to the invoice subtotal"),
          Map.entry("PAYMENT_NOT_FOUND", "We could not find that receipt"),
          Map.entry("PAYMENT_AMOUNT_INVALID", "A receipt amount must be greater than zero"),
          Map.entry("PAYMENT_ALLOCATION_EXCEEDS_INVOICE",
              "That allocation is more than the invoice still owes"),
          Map.entry("PAYMENT_ALLOCATION_EXCEEDS_RECEIPT",
              "The allocations are more than the receipt itself"),
          Map.entry("PAYMENT_ALREADY_VOID", "This receipt has already been voided"),
          Map.entry("REFUND_EXCEEDS_RECEIPT",
              "The refunds add up to more than the receipt being refunded"),
          Map.entry("PAYMENT_CURRENCY_MISMATCH",
              "This receipt is in a different currency to the invoice it is settling"),

          // ---- maintenance, expenses ----
          Map.entry("MAINTENANCE_NOT_FOUND", "We could not find that maintenance request"),
          Map.entry("DUPLICATE_MAINTENANCE_CODE",
              "A maintenance request with this code already exists"),
          Map.entry("MAINTENANCE_TRANSITION_NOT_ALLOWED",
              "The request cannot move to that status from where it stands"),
          Map.entry("VENDOR_NOT_FOUND", "We could not find that vendor"),
          Map.entry("VENDOR_NOT_EMPANELLED",
              "That vendor is not on the approved list for this category"),
          Map.entry("EXPENSE_NOT_FOUND", "We could not find that expense"),
          Map.entry("EXPENSE_RECOVERY_NOT_ALLOWED",
              "Only a recoverable expense can be marked as recovered"),
          Map.entry("EXPENSE_RECOVERY_EXCEEDS_TOTAL",
              "The recovered amount cannot be more than the expense itself"),

          // ---- lifecycle: already in the requested state ----
          // These arrived as inline sentences at their throw sites. They are centralized here because
          // the class's own rule is that one edit fixes the wording everywhere - a sentence scattered
          // through eight services is exactly the drift this map exists to prevent.
          //
          // NOTE: `Map.ofEntries` throws on a duplicate key at class initialization, so a repeated
          // entry is not a cosmetic mistake - it stops the application from starting. Every key below
          // is asserted unique by `ErrorMessagesTest`.
          Map.entry("PROPERTY_ALREADY_ARCHIVED", "This property has already been archived"),
          Map.entry("PROPERTY_TRANSITION_NOT_ALLOWED",
              "The property cannot move to that status from where it stands"),
          Map.entry("UNIT_ALREADY_ARCHIVED", "This unit has already been archived"),
          Map.entry("UNIT_TRANSITION_NOT_ALLOWED",
              "The unit cannot move to that status from where it stands"),
          Map.entry("UNIT_TRANSITION_REQUIRES_REASON", "A unit status change needs a reason"),
          Map.entry("OWNER_ALREADY_ARCHIVED", "This owner has already been archived"),
          Map.entry("TENANT_ALREADY_ARCHIVED", "This tenant has already been archived"),
          Map.entry("BUYER_ALREADY_ARCHIVED", "This buyer has already been archived"),
          Map.entry("OFFICE_ALREADY_ARCHIVED", "This office has already been archived"),
          Map.entry("AGENT_ALREADY_ARCHIVED", "This agent has already been archived"),
          Map.entry("VENDOR_ALREADY_ARCHIVED", "This vendor has already been archived"),
          Map.entry("EXPENSE_ALREADY_ARCHIVED", "This expense has already been archived"),
          Map.entry("PORTFOLIO_ALREADY_ARCHIVED", "This portfolio has already been archived"),
          Map.entry("DOCUMENT_ALREADY_ARCHIVED", "This document has already been archived"),
          Map.entry("LISTING_STATUS_UNCHANGED", "This listing is already in that state"),
          Map.entry("CONVERSATION_NOT_CLOSED", "This conversation is not closed"),

          // ---- duplicates the service layer checks before the database does ----
          Map.entry("DUPLICATE_BUILDING_CODE", "A building with this code already exists on this property"),
          Map.entry("DUPLICATE_UNIT_NUMBER", "A unit with this number already exists on this property"),
          Map.entry("DUPLICATE_SAVE", "That change has already been applied"),

          // ---- offices and agent assignment ----
          Map.entry("OFFICE_HEAD_OFFICE_EXISTS",
              "This firm already has a head office. Clear it before promoting another"),
          Map.entry("AGENT_PRIMARY_ALREADY_SET",
              "This property already has a primary agent for that period"),
          Map.entry("AGENT_ASSIGNMENT_NOT_FOUND", "We could not find that assignment"),
          Map.entry("AGENT_ASSIGNMENT_ALREADY_CLOSED", "That assignment has already been closed"),
          Map.entry("VENDOR_NOT_ASSIGNABLE",
              "That vendor is not available to take work - check its status and category"),

          // ---- sales lifecycle beyond the basics ----
          Map.entry("SALE_ALREADY_CONCLUDED", "This sale has already been concluded"),
          Map.entry("DUPLICATE_OPEN_SALE", "This buyer already has an open sale on this property"),
          Map.entry("SALE_ASSIGN_INACTIVE_AGENT",
              "That agent is not active and cannot be given a sale"),

          // ---- portfolio allocation ----
          Map.entry("PORTFOLIO_PROPERTY_NOT_FOUND",
              "This property is not currently allocated to that portfolio"),

          // ---- maintenance and expense recovery ----
          Map.entry("MAINTENANCE_ALREADY_ARCHIVED", "This maintenance request has already been archived"),
          Map.entry("MAINTENANCE_NEEDS_HOLDER",
              "A request cannot be marked as assigned without naming who holds it"),
          Map.entry("MAINTENANCE_NEEDS_RESOLUTION",
              "A request cannot be closed without being resolved first"),
          Map.entry("EXPENSE_NOT_RECOVERABLE",
              "This expense is not marked as recoverable, so nothing can be recovered against it"),

          // ---- imports ----
          Map.entry("IMPORT_NOT_COMMITTABLE",
              "This import is not in a state where it can be written to the portfolio"),

          // ---- sales ----
          Map.entry("SALE_NOT_FOUND", "We could not find that sale"),
          Map.entry("DUPLICATE_SALE_CODE", "A sale with this code already exists"),
          Map.entry("OFFER_NOT_FOUND", "We could not find that offer"),
          Map.entry("OFFER_ALREADY_ACCEPTED",
              "This sale already has an accepted offer"),
          Map.entry("OFFER_ALREADY_DECIDED", "This offer has already been decided"),
          Map.entry("SALE_TRANSITION_NOT_ALLOWED",
              "The sale cannot move to that stage from where it stands"),
          Map.entry("SALE_COMPLETION_REQUIRES_ACCEPTED_OFFER",
              "A sale cannot complete without an accepted offer"),
          Map.entry("SALE_AMOUNT_REQUIRED", "A completed sale needs an agreed price"),

          // ---- documents, portfolios, communication ----
          Map.entry("DOCUMENT_NOT_FOUND", "We could not find that document"),
          Map.entry("DUPLICATE_DOCUMENT_CODE", "A document with this code already exists"),
          Map.entry("DOCUMENT_ALREADY_SUPERSEDED",
              "This document has already been superseded by a later version"),
          Map.entry("DOCUMENT_SUPERSEDES_ITSELF", "A document cannot supersede itself"),
          Map.entry("PORTFOLIO_NOT_FOUND", "We could not find that portfolio"),
          Map.entry("DUPLICATE_PORTFOLIO_CODE", "A portfolio with this code already exists"),
          Map.entry("PROPERTY_ALREADY_IN_PORTFOLIO",
              "This property is already allocated to that portfolio"),
          Map.entry("CONVERSATION_NOT_FOUND", "We could not find that conversation"),
          Map.entry("CONVERSATION_ALREADY_CLOSED", "This conversation is already closed"),

          // ---- imports ----
          Map.entry("IMPORT_JOB_NOT_FOUND", "We could not find that import"),
          Map.entry("IMPORT_FILE_EMPTY", "No file was attached to the request"),
          Map.entry("IMPORT_FILE_TOO_LARGE", "That file is larger than this installation accepts"),
          Map.entry("IMPORT_FILE_TYPE_UNSUPPORTED",
              "That file type is not supported. Upload a CSV or Excel file"),
          Map.entry("IMPORT_FILE_CONTENT_INVALID",
              "The file's contents do not match its type"),
          Map.entry("IMPORT_TOO_MANY_ROWS", "That file has more rows than this installation accepts"),
          Map.entry("IMPORT_HEADER_MISSING", "The file has no recognisable header row"),
          Map.entry("IMPORT_REQUIRED_COLUMNS_MISSING",
              "The file is missing columns this import requires"),
          Map.entry("IMPORT_NO_VALID_ROWS", "None of the rows in that file could be imported"),
          Map.entry("IMPORT_ALREADY_CONFIRMED", "This import has already been confirmed"),
          Map.entry("IMPORT_NOT_CONFIRMABLE",
              "This import is not in a state where it can be confirmed"),
          Map.entry("IMPORT_IDEMPOTENCY_CONFLICT",
              "This import has already been submitted under a different request"));

  /**
   * The sentence for a code, or a neutral fallback.
   *
   * <p>Never returns the raw code: an internal identifier reaching a person's screen as
   * {@code SOME_UNMAPPED_CODE} is worse than a plain apology.</p>
   */
  public static String friendly(String code) {
    if (code == null || code.isBlank()) return FALLBACK;
    return FRIENDLY.getOrDefault(code, FALLBACK);
  }

  /** True when a code has wording of its own, which the tests assert for every code we throw. */
  public static boolean isKnown(String code) {
    return code != null && FRIENDLY.containsKey(code);
  }

  /**
   * Every code this map knows.
   *
   * <p>Exposed so a test can assert the <em>whole</em> set resolves to real wording rather than
   * checking a hand-maintained sample that silently falls behind the map.</p>
   */
  static java.util.Set<String> codes() {
    return FRIENDLY.keySet();
  }

  /** The one sentence an unrecognised code falls back to. */
  static String genericFallback() {
    return FALLBACK;
  }
}
