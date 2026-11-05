package com.meridian.realestate.domain;

/**
 * The trade a vendor practises - what work they can be called out for.
 *
 * <p>Exactly the values the {@code ck_vendors_category} constraint allows, in the order the
 * constraint lists them. The same list is reused by {@link MaintenanceCategory}, which is why a
 * request and a vendor can be matched on it: a plumbing fault is looked for among plumbing
 * vendors rather than in a free-text field that says "plumber" on one row and "Plumbing" on the
 * next.</p>
 *
 * <p>{@code GENERAL} exists because a good deal of work is not a trade - a broken door, a
 * furniture move - and forcing it into {@code CIVIL} would corrupt the category tallies the
 * maintenance report reads.</p>
 */
public enum VendorCategory {
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
