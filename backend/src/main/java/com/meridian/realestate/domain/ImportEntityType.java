package com.meridian.realestate.domain;

/**
 * The kind of record an import file creates, exactly as {@code ck_import_jobs_entity} enumerates it.
 *
 * <p>This list is read from the CHECK constraint the Flyway chain actually carries on
 * {@code import_jobs.entity_type}. The DDL does constrain the column, so the importer can only
 * ever stage the entity types named here, and adding another is a migration rather than a string
 * somebody typed.</p>
 */
public enum ImportEntityType {
  /** Properties, from the asset register. */
  PROPERTY,
  /** Units within a property. */
  UNIT,
  /** Owners. */
  OWNER,
  /** Tenants. */
  TENANT,
  /** Leads. */
  LEAD,
  /** Leases. */
  LEASE,
  /** Payments received or made. */
  PAYMENT
}
