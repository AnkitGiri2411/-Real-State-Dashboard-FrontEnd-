package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Building;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The building (block) API contract.
 *
 * <p><b>Why these records exist.</b> A block is a small record - a code, a name, a floor count - and
 * the entity carries a lazy {@code property} association that no response should expose. The shapes
 * here flatten the block's own property down to the two values a list needs (its id and its name) so
 * a client never receives an entity graph.</p>
 *
 * <p><b>The rule these records protect.</b> A building code is unique <em>within its property</em>
 * ({@code uq_buildings_property_code}), not globally - "Block A" exists in more than one property.
 * The service enforces that composite uniqueness rather than a global one, and the summary always
 * carries the property so a code is never read out of context.</p>
 */
public final class BuildingDtos {

  private BuildingDtos() {}

  /** One row of the building list, with how many units sit in it. */
  public record BuildingSummary(
      UUID id,
      UUID propertyId,
      String propertyName,
      String buildingCode,
      String name,
      Integer floors,
      Integer yearBuilt,
      long unitCount,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static BuildingSummary from(Building building, long unitCount) {
      return new BuildingSummary(
          building.getId(),
          building.getProperty() == null ? null : building.getProperty().getId(),
          building.getProperty() == null ? null : building.getProperty().getName(),
          building.getBuildingCode(),
          building.getName(),
          building.getFloors(),
          building.getYearBuilt(),
          unitCount,
          building.getCreatedAt(),
          building.getUpdatedAt());
    }
  }

  /** One building's full record. */
  public record BuildingDetail(
      UUID id,
      UUID propertyId,
      String propertyName,
      String buildingCode,
      String name,
      Integer floors,
      Integer yearBuilt,
      String description,
      long unitCount,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** Add a block to a property. */
  public record CreateBuildingRequest(
      @NotNull UUID propertyId,
      @NotBlank @Size(max = 30) String buildingCode,
      @NotBlank @Size(max = 150) String name,
      @Min(value = 0, message = "cannot be negative")
          @Max(value = 200, message = "must be 200 floors or fewer")
          Integer floors,
      @Min(value = 1700, message = "must be a year between 1700 and 2200")
          @Max(value = 2200, message = "must be a year between 1700 and 2200")
          Integer yearBuilt,
      @Size(max = 500) String description) {}

  /** Change a block. Every field is optional; only the ones supplied are applied. */
  public record UpdateBuildingRequest(
      @Size(max = 30) String buildingCode,
      @Size(max = 150) String name,
      @Min(value = 0, message = "cannot be negative")
          @Max(value = 200, message = "must be 200 floors or fewer")
          Integer floors,
      @Min(value = 1700, message = "must be a year between 1700 and 2200")
          @Max(value = 2200, message = "must be a year between 1700 and 2200")
          Integer yearBuilt,
      @Size(max = 500) String description) {}
}
