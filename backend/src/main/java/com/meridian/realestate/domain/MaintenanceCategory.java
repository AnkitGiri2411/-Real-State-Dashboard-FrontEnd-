package com.meridian.realestate.domain;

/**
 * What kind of fault a maintenance request describes.
 *
 * <p>Exactly the values the {@code ck_maintenance_requests_category} constraint allows. The list is
 * identical to {@link VendorCategory} on purpose: the category is what the request is matched
 * against to find a vendor, so the two have to be drawn from one vocabulary or the match silently
 * returns nothing.</p>
 *
 * <p>It is a separate enum rather than a shared one because the two columns are separately
 * constrained by the schema and may legitimately diverge later - a vendor may specialise in
 * {@code GENERAL} work while a request is never filed under it.</p>
 */
public enum MaintenanceCategory {
  PLUMBING,
  ELECTRICAL,
  HVAC,
  CIVIL,
  PAINTING,
  CARPENTRY,
  PEST_CONTROL,
  HOUSEKEEPING,
  SECURITY,
  LIFT,
  FIRE_SAFETY,
  LANDSCAPING,
  GENERAL,
  OTHER
}
