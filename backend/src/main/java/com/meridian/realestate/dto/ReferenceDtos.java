package com.meridian.realestate.dto;

import com.meridian.realestate.domain.PropertyType;
import com.meridian.realestate.domain.UnitType;
import java.util.UUID;

/**
 * The property-type and unit-type reference data contracts.
 *
 * <p><b>Why these records exist.</b> Categories are seeded by the schema migration - Apartment,
 * Villa, Land / Plot, and Studio, 2 BHK, Warehouse Bay - and never created through the API. They
 * exist for the pickers on the property and unit forms, so the only shapes needed are reads.</p>
 *
 * <p><b>The rule they protect.</b> A property type's {@code leasable} flag is a real business fact,
 * not a label: stock whose type is not normally let (a plot of land) is excluded from occupancy and
 * yield at the source. The read exposes the flag so a form can reflect it, rather than hiding it and
 * inviting a caller to guess.</p>
 */
public final class ReferenceDtos {

  private ReferenceDtos() {}

  /** One property category. */
  public record PropertyTypeView(
      UUID id, String code, String name, String description, boolean leasable, int displayOrder) {

    public static PropertyTypeView from(PropertyType type) {
      return new PropertyTypeView(
          type.getId(),
          type.getCode(),
          type.getName(),
          type.getDescription(),
          type.isLeasable(),
          type.getDisplayOrder());
    }
  }

  /** One unit category. */
  public record UnitTypeView(
      UUID id, String code, String name, String description, int displayOrder) {

    public static UnitTypeView from(UnitType type) {
      return new UnitTypeView(
          type.getId(),
          type.getCode(),
          type.getName(),
          type.getDescription(),
          type.getDisplayOrder());
    }
  }
}
