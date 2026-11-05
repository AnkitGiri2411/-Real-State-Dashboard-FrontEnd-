package com.meridian.realestate.service;

import java.util.Locale;

/**
 * The vocabulary of the audit trail: what a module is called and what an action is called.
 *
 * <p><b>Why this class exists.</b> {@code audit_logs.module} and {@code audit_logs.action} are
 * {@code varchar} columns the audit screen groups, filters and labels by, which means their values
 * are a vocabulary rather than free text. Written as string literals at each call site, that
 * vocabulary immediately disagrees with itself - {@code PROPERTIES} on one screen and
 * {@code PROPERTY} on another, {@code STATUS_CHANGE} and {@code STATUS_CHANGED} for the same event -
 * and a trail that cannot be filtered is a trail nobody reads. Every value the trail is written with
 * is therefore declared once, here, and referenced by name everywhere else.</p>
 *
 * <p><b>How the vocabulary was chosen.</b> The module strings are the product's own module names,
 * exactly as the permission seed ({@code V2}) spells them and as the seeded trail ({@code V23})
 * already writes them: {@code PROPERTIES}, {@code LEASES}, {@code PAYMENTS}, {@code IMPORT} and the
 * rest. That is deliberate - a member of staff who may "see properties" and an audit entry that
 * happened in the property module have to use the same word, or the audit screen's filters and the
 * permission that gates them drift apart. Where the product has no module for a concept - sign-in,
 * a permission change - this class names one rather than borrowing an unrelated module's name.</p>
 *
 * <p>Note the two shapes, because they are easy to confuse: a <em>module</em> is the area of the
 * product ({@link #PROPERTY}), while an <em>entity type</em> names the record an entry is about
 * ({@code "PROPERTY"}) and is what {@link #moduleFor(String)} maps from. The stored module value is
 * plural where the product's module names are plural, so {@link #PROPERTY} holds
 * {@code "PROPERTIES"} - it is the module, not the entity.</p>
 *
 * <p>Nothing here has behaviour beyond that mapping. An action name is a fact about what happened,
 * not a decision about what may happen; a class that also computed something could be wrong, and
 * this one only has to be right once.</p>
 */
public final class AuditActions {

  private AuditActions() {}

  // ---------------------------------------------------------------------------
  // Modules - the area of the product an entry belongs to. The values are the
  // product's module names (V2's permission modules, V23's seeded trail).
  // ---------------------------------------------------------------------------

  /** Sign-in, sign-out and session events. */
  public static final String IDENTITY = "IDENTITY";

  /** The property register. */
  public static final String PROPERTY = "PROPERTIES";

  /** The unit board and unit records. */
  public static final String UNIT = "UNITS";

  /** Owners and their holdings. */
  public static final String OWNER = "OWNERS";

  /** Tenant profiles. */
  public static final String TENANT = "TENANTS";

  /** Buyers and their requirements. */
  public static final String BUYER = "BUYERS";

  /** Agent profiles and their assignments. */
  public static final String AGENT = "AGENTS";

  /** Leads and their stages. */
  public static final String LEAD = "LEADS";

  /** Listings and their publication states. */
  public static final String LISTING = "LISTINGS";

  /** The viewing diary. */
  public static final String VIEWING = "VIEWINGS";

  /** Rental applications and their decisions. */
  public static final String APPLICATION = "APPLICATIONS";

  /** Leases, from draft to termination. */
  public static final String LEASE = "LEASES";

  /** Receipts and refunds. */
  public static final String PAYMENT = "PAYMENTS";

  /** Faults on the stock, their assignment and resolution. */
  public static final String MAINTENANCE = "MAINTENANCE";

  /** Property expenses. */
  public static final String EXPENSE = "EXPENSES";

  /** The sales pipeline, offers included. */
  public static final String SALE = "SALES";

  /** Portfolios and their allocations. */
  public static final String PORTFOLIO = "PORTFOLIOS";

  /** Document records. */
  public static final String DOCUMENT = "DOCUMENTS";

  /** Owner, tenant and prospect conversations. */
  public static final String COMMUNICATION = "COMMUNICATION";

  /** Data imports, from upload to commit. */
  public static final String IMPORT = "IMPORTS";

  /** Permission, role and credential changes - the entries an auditor reads first. */
  public static final String SECURITY = "SECURITY";

  /** Staff accounts. */
  public static final String STAFF = "STAFF";

  /** Roles and what they may do. */
  public static final String ROLES = "ROLES";

  /** Firm settings. */
  public static final String SETTINGS = "SETTINGS";

  /** Reading the trail itself. */
  public static final String AUDIT = "AUDIT";

  /** Reports. */
  public static final String REPORTS = "REPORTS";

  /** Notifications. */
  public static final String NOTIFICATIONS = "NOTIFICATIONS";

  // ---------------------------------------------------------------------------
  // Actions - what happened. A verb, in the past tense where an event is meant
  // and in the imperative where a CRUD operation is meant, matching how the
  // seeded trail is worded.
  // ---------------------------------------------------------------------------

  /** Somebody signed in. Never carries how: no password, hash or token value. */
  public static final String LOGIN = "LOGIN";

  /** Somebody signed out. */
  public static final String LOGOUT = "LOGOUT";

  /** A record was created. */
  public static final String CREATE = "CREATE";

  /** A record's details were changed. */
  public static final String UPDATE = "UPDATE";

  /** A record was archived. Nothing in this product is hard-deleted. */
  public static final String DELETE = "DELETE";

  /** A record moved between lifecycle states. */
  public static final String STATUS_CHANGE = "STATUS_CHANGE";

  /** Work was assigned to somebody. */
  public static final String ASSIGNMENT = "ASSIGNMENT";

  /** Money came in against an invoice. */
  public static final String PAYMENT_RECORDED = "PAYMENT_RECORDED";

  /** A receipt was reversed. */
  public static final String PAYMENT_VOIDED = "PAYMENT_VOIDED";

  /** A lease was drafted. */
  public static final String LEASE_CREATED = "LEASE_CREATED";

  /** A lease moved between statuses. */
  public static final String LEASE_STATUS_CHANGED = "LEASE_STATUS_CHANGED";

  /** A lease was renewed. */
  public static final String LEASE_RENEWED = "LEASE_RENEWED";

  /** A sale moved between pipeline stages. */
  public static final String SALE_STAGE_CHANGED = "SALE_STAGE_CHANGED";

  /** An offer was accepted. */
  public static final String OFFER_ACCEPTED = "OFFER_ACCEPTED";

  /** A rental application was approved or rejected. */
  public static final String APPLICATION_DECIDED = "APPLICATION_DECIDED";

  /** A viewing was completed and its outcome recorded. */
  public static final String VIEWING_COMPLETED = "VIEWING_COMPLETED";

  /** A document was replaced by a later version. */
  public static final String DOCUMENT_SUPERSEDED = "DOCUMENT_SUPERSEDED";

  /** An import was committed to the live records. */
  public static final String IMPORT_COMMITTED = "IMPORT_COMMITTED";

  /**
   * A permission, role or credential change.
   *
   * <p>The one action an owner or an auditor is expected to read: it says that what somebody may do
   * has changed, which is the event that makes every later entry by that account mean something
   * different from the ones before it.</p>
   */
  public static final String PERMISSION_SENSITIVE = "PERMISSION_SENSITIVE";

  /**
   * The module an entity type belongs to.
   *
   * <p>Call sites usually have the entity they just wrote in hand ({@code "LEASE"}) and need the
   * module to record it against ({@link #LEASE} = {@code "LEASES"}); this keeps that mapping in one
   * place instead of inviting each caller to guess the plural. The mapping is the product's own: the
   * seeded trail writes {@code entity_type = 'LEASE'} alongside {@code module = 'LEASES'}.</p>
   *
   * <p>Case-insensitive, and both the singular and plural forms of a name are accepted. An entity
   * type outside this vocabulary returns {@code null} rather than the input echoed back: putting an
   * unnamed module into the trail would look like a module nobody can filter by, whereas a null
   * fails at the column that requires it and makes the caller name its module deliberately.</p>
   *
   * @param entityType the entity type an entry is about, as stored in
   *     {@code audit_logs.entity_type}
   * @return the module constant for it, or {@code null} when the product has no module for it
   */
  public static String moduleFor(String entityType) {
    if (entityType == null || entityType.isBlank()) return null;
    return switch (entityType.trim().toUpperCase(Locale.ROOT)) {
      case "PROPERTY", "PROPERTIES" -> PROPERTY;
      case "UNIT", "UNITS" -> UNIT;
      case "OWNER", "OWNERS" -> OWNER;
      case "TENANT", "TENANTS" -> TENANT;
      case "BUYER", "BUYERS" -> BUYER;
      case "AGENT", "AGENT_PROFILE", "AGENTS" -> AGENT;
      case "LEAD", "LEADS" -> LEAD;
      case "LISTING", "LISTINGS" -> LISTING;
      case "VIEWING", "VIEWINGS" -> VIEWING;
      case "APPLICATION", "RENTAL_APPLICATION", "APPLICATIONS" -> APPLICATION;
      case "LEASE", "LEASE_RENEWAL", "LEASES" -> LEASE;
      case "PAYMENT", "PAYMENT_ALLOCATION", "RECEIPT", "PAYMENTS" -> PAYMENT;
      case "MAINTENANCE", "MAINTENANCE_REQUEST" -> MAINTENANCE;
      case "EXPENSE", "PROPERTY_EXPENSE", "EXPENSES" -> EXPENSE;
      case "SALE", "SALES_OPPORTUNITY", "OFFER", "SALES" -> SALE;
      case "PORTFOLIO", "PORTFOLIOS" -> PORTFOLIO;
      case "DOCUMENT", "DOCUMENTS" -> DOCUMENT;
      case "CONVERSATION", "CONVERSATION_MESSAGE", "COMMUNICATION" -> COMMUNICATION;
      case "IMPORT", "IMPORT_JOB", "IMPORTS", "DATA" -> IMPORT;
      case "USER", "STAFF" -> STAFF;
      case "ROLE", "PERMISSION", "ROLES" -> ROLES;
      case "SETTINGS" -> SETTINGS;
      case "AUDIT" -> AUDIT;
      case "REPORT", "REPORTS" -> REPORTS;
      case "NOTIFICATION", "NOTIFICATIONS" -> NOTIFICATIONS;
      default -> null;
    };
  }
}
