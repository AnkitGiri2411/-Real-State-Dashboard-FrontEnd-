package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Owner;
import com.meridian.realestate.domain.PropertyOwnership;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
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
 * The owner register's API contract, and the property-ownership sub-resource that hangs off it.
 *
 * <p><b>Why these records exist.</b> An {@code Owner} entity is never returned from an endpoint: it
 * carries the housekeeping columns, and a caller that received it could not tell which fields are a
 * promise. Each record here is the deliberate subset the register actually promises, with every
 * enum flattened to its name so a client never has to know the class or the ordinal. The ownership
 * records are the same idea for a different table: a holding is a relationship with a share and a
 * period, and it is stated here as exactly that.</p>
 *
 * <p><b>The rule these records protect.</b> The shares in force for one property must total exactly
 * 100%. That rule spans rows and therefore cannot be a check constraint - see
 * {@link com.meridian.realestate.domain.PropertyOwnership} - so it is enforced in the service, and
 * the shapes here exist to make it expressible: a share carries an explicit
 * {@code ownershipPercentage} bounded to {@code (0, 100]}, and a period ({@code effectiveFrom} /
 * {@code effectiveTo}) so that "in force" is a question with an answer rather than a guess. A
 * closed holding is dated rather than removed.</p>
 *
 * <p><b>Money and percentages are {@link BigDecimal} only.</b> None of these records declares a
 * {@code double}; a share is a decimal, and a decimal that drifted by a fraction would make a
 * property's holdings total 99.99 or 100.01 and the register unreconcilable.</p>
 */
public final class OwnerDtos {

  private OwnerDtos() {}

  /** The closed set of owner types the schema's {@code ck_owners_type} allows. */
  private static final String OWNER_TYPE_SET =
      "INDIVIDUAL|COMPANY|PARTNERSHIP|TRUST|JOINT_VENTURE|GOVERNMENT|OTHER";

  /** The closed set of owner statuses the schema's {@code ck_owners_status} allows. */
  private static final String OWNER_STATUS_SET = "ACTIVE|INACTIVE";

  /** The closed set of relationships the schema's {@code ck_property_ownerships_relationship} allows. */
  private static final String RELATIONSHIP_SET =
      "SOLE_OWNER|CO_OWNER|MAJORITY_HOLDER|MINORITY_HOLDER|BENEFICIARY|DEVELOPMENT_PARTNER|LESSOR|OTHER";

  // ---------------------------------------------------------------------------
  // Owners - reads
  // ---------------------------------------------------------------------------

  /** One row of the owner register: enough to identify an owner and read their headline state. */
  public record OwnerSummary(
      UUID id,
      String ownerCode,
      String ownerType,
      String displayName,
      String primaryContactName,
      String email,
      String phone,
      String city,
      String state,
      String status,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static OwnerSummary from(Owner owner) {
      return new OwnerSummary(
          owner.getId(),
          owner.getOwnerCode(),
          name(owner.getOwnerType()),
          owner.getDisplayName(),
          owner.getPrimaryContactName(),
          owner.getEmail(),
          owner.getPhone(),
          owner.getCity(),
          owner.getState(),
          name(owner.getStatus()),
          owner.getCreatedAt(),
          owner.getUpdatedAt());
    }
  }

  /**
   * One owner's full record, with every holding they carry across the portfolio.
   *
   * <p>The holdings are carried on the detail rather than left to a second request because the
   * owner page is "who is this and what do they hold", and {@code openHoldingCount} is the figure
   * the header shows beside the name.</p>
   */
  public record OwnerDetail(
      UUID id,
      String ownerCode,
      String ownerType,
      String displayName,
      String primaryContactName,
      String email,
      String phone,
      String alternatePhone,
      String idReference,
      String taxReference,
      String addressLine1,
      String addressLine2,
      String locality,
      String city,
      String state,
      String postalCode,
      String country,
      String status,
      String notes,
      long holdingCount,
      long openHoldingCount,
      List<OwnershipShare> holdings,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  // ---------------------------------------------------------------------------
  // Property ownership
  // ---------------------------------------------------------------------------

  /**
   * One owner's holding in one property, for a period, with a share.
   *
   * <p>{@code open} is the same fact as {@code effectiveTo == null} stated once so a client does not
   * have to know the convention; a closed holding keeps its dates and stays readable as history.</p>
   */
  public record OwnershipShare(
      UUID ownershipId,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID ownerId,
      String ownerCode,
      String ownerName,
      BigDecimal percentage,
      String relationship,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean open,
      String notes) {

    public static OwnershipShare from(PropertyOwnership share) {
      var property = share.getProperty();
      var owner = share.getOwner();
      return new OwnershipShare(
          share.getId(),
          property == null ? null : property.getId(),
          property == null ? null : property.getPropertyCode(),
          property == null ? null : property.getName(),
          owner == null ? null : owner.getId(),
          owner == null ? null : owner.getOwnerCode(),
          owner == null ? null : owner.getDisplayName(),
          share.getOwnershipPercentage(),
          name(share.getRelationship()),
          share.getEffectiveFrom(),
          share.getEffectiveTo(),
          share.isOpen(),
          share.getNotes());
    }
  }

  /**
   * The result of checking that a property's shares total exactly 100%.
   *
   * <p>Returned by {@code GET /api/v1/properties/{propertyId}/ownerships/validate} when the shares
   * are correct. When they are not, the service raises a {@code 409} named
   * {@code OWNERSHIP_NOT_ONE_HUNDRED} whose {@code details} carry the same two figures this record
   * carries - the total and the shortfall - so a caller can render the gap either way.</p>
   */
  public record OwnershipValidation(
      UUID propertyId,
      BigDecimal total,
      BigDecimal shortfall,
      BigDecimal surplus,
      boolean valid,
      int shareCount,
      List<OwnershipShare> shares) {}

  // ---------------------------------------------------------------------------
  // Owners - writes
  // ---------------------------------------------------------------------------

  /** Add an owner to the register. The owner code is minted by the service, never supplied. */
  public record CreateOwnerRequest(
      @NotBlank
          @Pattern(regexp = OWNER_TYPE_SET, message = "must be an owner type")
          String ownerType,
      @NotBlank @Size(max = 200) String displayName,
      @Size(max = 200) String primaryContactName,
      @Email @Size(max = 255) String email,
      @Size(max = 30) String phone,
      @Size(max = 30) String alternatePhone,
      @Size(max = 60) String idReference,
      @Size(max = 60) String taxReference,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      @Pattern(regexp = OWNER_STATUS_SET, message = "must be an owner status") String status,
      String notes) {}

  /** Change an owner. Every field is optional; only the ones supplied are applied. */
  public record UpdateOwnerRequest(
      @Pattern(regexp = OWNER_TYPE_SET, message = "must be an owner type") String ownerType,
      @Size(max = 200) String displayName,
      @Size(max = 200) String primaryContactName,
      @Email @Size(max = 255) String email,
      @Size(max = 30) String phone,
      @Size(max = 30) String alternatePhone,
      @Size(max = 60) String idReference,
      @Size(max = 60) String taxReference,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      @Pattern(regexp = OWNER_STATUS_SET, message = "must be an owner status") String status,
      String notes) {}

  // ---------------------------------------------------------------------------
  // Ownership - writes
  // ---------------------------------------------------------------------------

  /**
   * Record an owner's holding in a property.
   *
   * <p>The percentage is bounded here to the same {@code (0, 100]} the per-row check constraint
   * enforces, so a caller learns the range from the response rather than from a database error. The
   * per-property total is not bounded here because it spans rows; the service refuses a change that
   * would push the open total above 100.</p>
   */
  public record CreateOwnershipRequest(
      @NotNull UUID ownerId,
      @NotNull
          @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          @DecimalMax(value = "100", message = "cannot be more than 100")
          BigDecimal ownershipPercentage,
      @Pattern(regexp = RELATIONSHIP_SET, message = "must be an ownership relationship")
          String relationship,
      @NotNull LocalDate effectiveFrom,
      LocalDate effectiveTo,
      @Size(max = 500) String notes) {}

  /** Change a holding. Every field is optional; only the ones supplied are applied. */
  public record UpdateOwnershipRequest(
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          @DecimalMax(value = "100", message = "cannot be more than 100")
          BigDecimal ownershipPercentage,
      @Pattern(regexp = RELATIONSHIP_SET, message = "must be an ownership relationship")
          String relationship,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      @Size(max = 500) String notes) {}

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }
}
