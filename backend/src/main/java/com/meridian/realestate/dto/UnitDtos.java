package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Unit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The unit register's API contract: the unit list, the unit board, the occupancy figures and the
 * writes that change a unit.
 *
 * <p><b>Why these records exist.</b> Units are the busiest records in the product and their entity
 * carries three lazy associations (property, building, unit type). Returning the entity would leak
 * an object graph and force every client to know the model; the records here flatten each
 * association to the two or three values a screen actually prints, and expose {@code leasable} and
 * {@code status} as separate facts.</p>
 *
 * <p><b>The rule these records protect.</b> Leasability and lifecycle are not the same thing. A
 * common area is a real unit that is simply not offered to let, and the database refuses to store a
 * non-lettable unit in a lettable state ({@code ck_units_not_lettable_status}). The request shapes
 * therefore carry both, and the service keeps them consistent rather than letting a caller store a
 * state the database would reject as an opaque constraint error.</p>
 *
 * <p><b>The board's shape follows how the desk reads it.</b> The unit board groups by building and
 * then by floor, because that is the physical stack a supervisor walks. The response nests exactly
 * that way - buildings, each with floors, each with unit cards - so the view does not re-group what
 * the query already ordered.</p>
 */
public final class UnitDtos {

  private UnitDtos() {}

  /** The closed set of statuses the schema's {@code ck_units_status} allows. */
  private static final String STATUS_SET =
      "AVAILABLE|RESERVED|OCCUPIED|UNDER_MAINTENANCE|LISTED|UNDER_CONTRACT|SOLD|NOT_LETTABLE";

  private static final String FURNISHING_SET = "UNFURNISHED|SEMI_FURNISHED|FURNISHED";
  private static final String FACING_SET = "N|S|E|W|NE|NW|SE|SW";

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** One row of the unit register. */
  public record UnitSummary(
      UUID id,
      String unitCode,
      String unitNumber,
      UUID propertyId,
      String propertyName,
      UUID buildingId,
      String buildingCode,
      String buildingName,
      UUID unitTypeId,
      String unitTypeCode,
      String unitTypeName,
      int floorNumber,
      String floorLabel,
      int bedrooms,
      int bathrooms,
      int balconies,
      int parkingSlots,
      String furnishing,
      String facing,
      String status,
      boolean leasable,
      BigDecimal askingRent,
      BigDecimal askingPrice) {

    public static UnitSummary from(Unit unit) {
      return new UnitSummary(
          unit.getId(),
          unit.getUnitCode(),
          unit.getUnitNumber(),
          unit.getProperty() == null ? null : unit.getProperty().getId(),
          unit.getProperty() == null ? null : unit.getProperty().getName(),
          unit.getBuilding() == null ? null : unit.getBuilding().getId(),
          unit.getBuilding() == null ? null : unit.getBuilding().getBuildingCode(),
          unit.getBuilding() == null ? null : unit.getBuilding().getName(),
          unit.getUnitType() == null ? null : unit.getUnitType().getId(),
          unit.getUnitType() == null ? null : unit.getUnitType().getCode(),
          unit.getUnitType() == null ? null : unit.getUnitType().getName(),
          unit.getFloorNumber(),
          unit.getFloorLabel(),
          unit.getBedrooms(),
          unit.getBathrooms(),
          unit.getBalconies(),
          unit.getParkingSlots(),
          unit.getFurnishing() == null ? null : unit.getFurnishing().name(),
          unit.getFacing() == null ? null : unit.getFacing().name(),
          unit.getStatus() == null ? null : unit.getStatus().name(),
          unit.isLeasable(),
          unit.getAskingRent(),
          unit.getAskingPrice());
    }
  }

  /** One unit's full record, including the lease currently in force on it, if any. */
  public record UnitDetail(
      UUID id,
      String unitCode,
      String unitNumber,
      UUID propertyId,
      String propertyName,
      UUID buildingId,
      String buildingCode,
      String buildingName,
      UUID unitTypeId,
      String unitTypeCode,
      String unitTypeName,
      int floorNumber,
      String floorLabel,
      int bedrooms,
      int bathrooms,
      int balconies,
      int parkingSlots,
      BigDecimal carpetAreaSqft,
      BigDecimal builtUpAreaSqft,
      String furnishing,
      String facing,
      String status,
      boolean leasable,
      BigDecimal askingRent,
      BigDecimal askingPrice,
      UnitLeaseSummary currentLease,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /**
   * The lease currently in force on a unit, as far as the unit module may see it.
   *
   * <p>Read-only and deliberately narrow: the unit board wants to answer "is this let, to whom, and
   * until when" without joining the whole lease. The lease module owns the record; this is a summary
   * of it. {@code tenantName} is present only when the tenant profile is reachable - a missing name
   * is left absent rather than invented.</p>
   */
  public record UnitLeaseSummary(
      UUID unitId,
      boolean let,
      UUID leaseId,
      String leaseCode,
      String status,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal rentAmount,
      String rentFrequency,
      UUID tenantId,
      String tenantName) {}

  /** The unit board: a property's units grouped by building and then by floor. */
  public record UnitBoard(UUID propertyId, String propertyCode, List<BuildingGroup> buildings) {}

  /** The blocks of a property. A unit in no block is grouped under {@code buildingId == null}. */
  public record BuildingGroup(
      UUID buildingId, String buildingCode, String buildingName, List<FloorGroup> floors) {}

  /** One floor of one block, ordered by floor then unit number - the order a person walks it. */
  public record FloorGroup(int floorNumber, String floorLabel, List<UnitCard> units) {}

  /** One tile on the unit board, carrying the facts the board colours and groups by. */
  public record UnitCard(
      UUID id,
      String unitCode,
      String unitNumber,
      UUID buildingId,
      String unitTypeCode,
      String unitTypeName,
      int floorNumber,
      String floorLabel,
      int bedrooms,
      String status,
      boolean leasable,
      boolean occupied,
      BigDecimal askingRent) {}

  /**
   * The occupancy figures for one property.
   *
   * <p>Occupancy is computed over the leasable stock only, and the percentage is guarded: zero
   * leasable units reads {@code 0%}, never a divide-by-zero.</p>
   */
  public record UnitOccupancy(
      UUID propertyId,
      long totalUnits,
      long leasableUnits,
      long occupiedUnits,
      long availableUnits,
      long reservedUnits,
      long underMaintenanceUnits,
      long notLettableUnits,
      int occupancyPercent) {}

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /** Add a unit to the stock. */
  public record CreateUnitRequest(
      @NotNull UUID propertyId,
      UUID buildingId,
      @NotNull UUID unitTypeId,
      @NotBlank @Size(max = 30) String unitNumber,
      @Min(value = -10, message = "must be a floor between -10 and 200")
          @Max(value = 200, message = "must be a floor between -10 and 200")
          Integer floorNumber,
      @Size(max = 30) String floorLabel,
      @Min(value = 0, message = "must be between 0 and 50")
          @Max(value = 50, message = "must be between 0 and 50")
          Integer bedrooms,
      @Min(value = 0, message = "must be between 0 and 50")
          @Max(value = 50, message = "must be between 0 and 50")
          Integer bathrooms,
      @Min(value = 0, message = "must be between 0 and 50")
          @Max(value = 50, message = "must be between 0 and 50")
          Integer balconies,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal carpetAreaSqft,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal builtUpAreaSqft,
      @Min(value = 0, message = "must be between 0 and 500")
          @Max(value = 500, message = "must be between 0 and 500")
          Integer parkingSlots,
      @Pattern(regexp = FURNISHING_SET, message = "must be UNFURNISHED, SEMI_FURNISHED or FURNISHED")
          String furnishing,
      @Pattern(regexp = FACING_SET, message = "must be a compass direction") String facing,
      @Pattern(regexp = STATUS_SET, message = "must be a unit status") String status,
      Boolean leasable,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal askingRent,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal askingPrice) {}

  /** Change a unit. Every field is optional; only the ones supplied are applied. */
  public record UpdateUnitRequest(
      UUID buildingId,
      UUID unitTypeId,
      @Size(max = 30) String unitNumber,
      @Min(value = -10, message = "must be a floor between -10 and 200")
          @Max(value = 200, message = "must be a floor between -10 and 200")
          Integer floorNumber,
      @Size(max = 30) String floorLabel,
      @Min(value = 0, message = "must be between 0 and 50")
          @Max(value = 50, message = "must be between 0 and 50")
          Integer bedrooms,
      @Min(value = 0, message = "must be between 0 and 50")
          @Max(value = 50, message = "must be between 0 and 50")
          Integer bathrooms,
      @Min(value = 0, message = "must be between 0 and 50")
          @Max(value = 50, message = "must be between 0 and 50")
          Integer balconies,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal carpetAreaSqft,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal builtUpAreaSqft,
      @Min(value = 0, message = "must be between 0 and 500")
          @Max(value = 500, message = "must be between 0 and 500")
          Integer parkingSlots,
      @Pattern(regexp = FURNISHING_SET, message = "must be UNFURNISHED, SEMI_FURNISHED or FURNISHED")
          String furnishing,
      @Pattern(regexp = FACING_SET, message = "must be a compass direction") String facing,
      Boolean leasable,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal askingRent,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal askingPrice) {}

  /**
   * Move a unit between lifecycle states.
   *
   * <p>Kept apart from {@link UpdateUnitRequest} on purpose: a status change is recorded in the
   * unit's status history, and it has its own permission ({@code UNIT_STATUS_CHANGE}). Folding it
   * into an ordinary edit would let a caller move a unit without a history row and without the
   * capability that guards occupancy.</p>
   */
  public record ChangeStatusRequest(
      @NotBlank
          @Pattern(regexp = STATUS_SET, message = "must be a unit status")
          String status,
      @Size(max = 500) String reason) {}
}
