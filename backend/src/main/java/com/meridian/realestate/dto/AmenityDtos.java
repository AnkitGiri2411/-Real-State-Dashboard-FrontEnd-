package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Amenity;
import java.util.List;
import java.util.UUID;

/**
 * The amenity reference data contract.
 *
 * <p><b>Why this record exists.</b> An amenity is the product's own vocabulary of facilities - power
 * backup, a lift, a clubhouse - seeded by the schema migration because every installation needs the
 * same list. It is lookup data, not a business record: nothing creates, edits or archives one
 * through the API, so the only shape needed is the read.</p>
 *
 * <p><b>The rule it protects.</b> Amenities are grouped by their {@code category} string (utility,
 * access, security, parking, logistics, lifestyle), which the interface uses to lay out the property
 * page. Exposing the category and the display order lets the properties page offer the facilities in
 * the intended order without knowing anything about the table they came from.</p>
 */
public final class AmenityDtos {

  private AmenityDtos() {}

  /** One facility a property can offer. */
  public record AmenityView(
      UUID id, String code, String name, String category, int displayOrder) {

    public static AmenityView from(Amenity amenity) {
      return new AmenityView(
          amenity.getId(),
          amenity.getCode(),
          amenity.getName(),
          amenity.getCategory(),
          amenity.getDisplayOrder());
    }
  }

  /** The facilities grouped by category, in display order - how the property page reads them. */
  public record AmenityGroup(String category, List<AmenityView> amenities) {}
}
