package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Property;
import jakarta.validation.constraints.DecimalMax;
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
import java.util.UUID;

/**
 * The property register's API contract: what the register lists, what one property reads as, and
 * what it takes to create, change and move one.
 *
 * <p><b>Why these records exist.</b> A {@code Property} entity is never returned from an endpoint.
 * The entity carries a lazy {@code propertyType} association and the housekeeping columns, and a
 * caller that received it could not tell which of its fields are a promise and which are an
 * implementation detail. Each record here is the deliberate subset the register actually promises,
 * with the enum flattened to its name so a client never has to know the ordinal or the class.</p>
 *
 * <p><b>The rule these records protect.</b> Position is all-or-nothing. {@code latitude} and
 * {@code longitude} are both optional, but a request that carries one and not the other is refused
 * before it reaches the database, where the paired constraint would otherwise reject it as an
 * opaque error. The map is a real projection over real coordinates, so half a position is never a
 * state worth saving - and a marker placed at a guessed point would be a lie the interface then
 * draws.</p>
 *
 * <p><b>Money is {@link BigDecimal} only.</b> No amount here is a {@code double}. Values are
 * declared at the scale the schema stores; the service rounds them with
 * {@code MoneyPolicy.money} before writing.</p>
 */
public final class PropertyDtos {

  private PropertyDtos() {}

  /** The closed set of statuses the schema's {@code ck_properties_status} allows. */
  private static final String STATUS_SET =
      "DRAFT|LISTED|AVAILABLE_ACTIVE|UNDER_CONTRACT|LEASED|SOLD|INACTIVE";

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** One row of the register: enough to identify a property and read its headline value. */
  public record PropertySummary(
      UUID id,
      String propertyCode,
      String name,
      UUID propertyTypeId,
      String propertyTypeCode,
      String propertyTypeName,
      String status,
      String city,
      String locality,
      String state,
      String country,
      BigDecimal currentValuation,
      BigDecimal acquisitionValue,
      boolean positioned,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static PropertySummary from(Property property) {
      return new PropertySummary(
          property.getId(),
          property.getPropertyCode(),
          property.getName(),
          property.getPropertyType() == null ? null : property.getPropertyType().getId(),
          property.getPropertyType() == null ? null : property.getPropertyType().getCode(),
          property.getPropertyType() == null ? null : property.getPropertyType().getName(),
          property.getStatus() == null ? null : property.getStatus().name(),
          property.getCity(),
          property.getLocality(),
          property.getState(),
          property.getCountry(),
          property.getCurrentValuation(),
          property.getAcquisitionValue(),
          property.hasPosition(),
          property.getCreatedAt(),
          property.getUpdatedAt());
    }
  }

  /**
   * One property's full record, with the counts the property page shows beside it.
   *
   * <p>The counts (buildings, units, documents, media) come back on the detail so the header does
   * not have to run four more requests to draw itself. They are counts, not the rows: the rows each
   * have their own endpoint and their own permission.</p>
   */
  public record PropertyDetail(
      UUID id,
      String propertyCode,
      String name,
      UUID propertyTypeId,
      String propertyTypeCode,
      String propertyTypeName,
      boolean propertyTypeLeasable,
      String status,
      String addressLine1,
      String addressLine2,
      String locality,
      String city,
      String state,
      String postalCode,
      String country,
      BigDecimal latitude,
      BigDecimal longitude,
      boolean positioned,
      String description,
      Integer yearBuilt,
      BigDecimal landAreaSqft,
      BigDecimal builtAreaSqft,
      BigDecimal acquisitionValue,
      BigDecimal currentValuation,
      LocalDate valuationDate,
      long buildingCount,
      long unitCount,
      long documentCount,
      long mediaCount,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /**
   * The property's operational metrics.
   *
   * <p><b>Occupancy is guarded, never a divide-by-zero.</b> A property with no leasable units reads
   * {@code 0%}, not {@code NaN} - a plot of land or a property whose stock is all common area is a
   * real state, and an occupancy percentage that cannot be computed is honestly zero rather than an
   * error.</p>
   *
   * <p><b>The rent roll is the asking rent over the leasable, live units.</b> It is the monthly
   * figure the desk is asking across the lettable stock, summed from {@code asking_rent} over the
   * units where {@code leasable = true} and not archived - the same set the occupancy denominator is
   * taken from, so the headline rent and the occupancy share one population and cannot disagree
   * about which units they describe. The alternative - the running lease's contractual rent - is a
   * different question (what is billed) and belongs to the rent module, not the register.</p>
   */
  public record PropertyMetrics(
      UUID propertyId,
      String propertyCode,
      long unitCount,
      long leasableUnits,
      long occupiedUnits,
      int occupancyPercent,
      BigDecimal monthlyRentRoll,
      BigDecimal currentValuation,
      BigDecimal acquisitionValue,
      BigDecimal landAreaSqft,
      BigDecimal builtAreaSqft) {}

  /**
   * One marker on the portfolio map.
   *
   * <p>Only properties with a real coordinate pair ever appear here - the query behind this is
   * filtered to positioned properties, and nothing in the service invents a coordinate for the ones
   * that are not. A property with no position is simply absent from the map, which is the honest
   * answer rather than a marker at a guessed place.</p>
   */
  public record MapMarker(
      UUID id,
      String propertyCode,
      String name,
      String city,
      String status,
      BigDecimal latitude,
      BigDecimal longitude,
      BigDecimal currentValuation,
      int occupancyPercent) {}

  /** A facility offered by a property, with the per-property note attached to it. */
  public record AmenityLink(
      UUID amenityId, String code, String name, String category, String notes) {}

  /** One owner's holding in a property, as read from the ownership register. */
  public record OwnershipShare(
      UUID ownershipId,
      UUID ownerId,
      String ownerCode,
      String ownerName,
      BigDecimal percentage,
      String relationship,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean open) {}

  /** How many documents are filed against a property. The documents themselves are another module. */
  public record DocumentCount(long documents) {}

  /** One status transition the property has made. */
  public record StatusHistoryEntry(
      UUID id,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedBy,
      LocalDateTime changedAt) {}

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /**
   * Create a property.
   *
   * <p>{@code country} defaults to India in the schema, so it is optional here. {@code status}
   * defaults to {@code DRAFT}; a caller may open a property directly in a later state, and the
   * service records the first transition.</p>
   */
  public record CreatePropertyRequest(
      @NotBlank @Size(max = 180) String name,
      @NotNull UUID propertyTypeId,
      @Pattern(regexp = STATUS_SET, message = "must be a property status") String status,
      @NotBlank @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @NotBlank @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      @DecimalMin(value = "-90", message = "must be a latitude between -90 and 90")
          @DecimalMax(value = "90", message = "must be a latitude between -90 and 90")
          BigDecimal latitude,
      @DecimalMin(value = "-180", message = "must be a longitude between -180 and 180")
          @DecimalMax(value = "180", message = "must be a longitude between -180 and 180")
          BigDecimal longitude,
      String description,
      @Min(value = 1700, message = "must be a year between 1700 and 2200")
          @Max(value = 2200, message = "must be a year between 1700 and 2200")
          Integer yearBuilt,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal landAreaSqft,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal builtAreaSqft,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal acquisitionValue,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal currentValuation,
      LocalDate valuationDate) {}

  /** Change a property. Every field is optional; only the ones supplied are applied. */
  public record UpdatePropertyRequest(
      @Size(max = 180) String name,
      UUID propertyTypeId,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      @DecimalMin(value = "-90", message = "must be a latitude between -90 and 90")
          @DecimalMax(value = "90", message = "must be a latitude between -90 and 90")
          BigDecimal latitude,
      @DecimalMin(value = "-180", message = "must be a longitude between -180 and 180")
          @DecimalMax(value = "180", message = "must be a longitude between -180 and 180")
          BigDecimal longitude,
      String description,
      @Min(value = 1700, message = "must be a year between 1700 and 2200")
          @Max(value = 2200, message = "must be a year between 1700 and 2200")
          Integer yearBuilt,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal landAreaSqft,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal builtAreaSqft,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal acquisitionValue,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal currentValuation,
      LocalDate valuationDate) {}

  /**
   * Move a property between lifecycle states.
   *
   * <p>A reason is optional but travels with the transition into the status history, which is the
   * point of having a history at all: "listed on the 3rd" is a fact, "listed on the 3rd because the
   * owner instructed it" is the fact the desk actually needs months later.</p>
   */
  public record ChangeStatusRequest(
      @NotBlank
          @Pattern(regexp = STATUS_SET, message = "must be a property status")
          String status,
      @Size(max = 500) String reason) {}

  /** Attach a facility to a property, with an optional property-specific note. */
  public record AttachAmenityRequest(
      @NotNull UUID amenityId, @Size(max = 255) String notes) {}
}
