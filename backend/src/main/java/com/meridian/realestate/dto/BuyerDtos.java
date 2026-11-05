package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.BuyerRequirement;
import com.meridian.realestate.domain.Offer;
import com.meridian.realestate.domain.Viewing;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The buyer register's API contract, including the requirement (brief) CRUD that hangs off it.
 *
 * <p><b>Why these records exist.</b> A buyer carries a brief rather than a set of columns on the
 * buyer row, because the brief changes over time and its history explains why a property was or was
 * not shown. That is why {@link RequirementRecord} exists as a first-class shape with its own
 * {@code isActive} flag: a superseded brief is deactivated, never deleted, so it stays readable as
 * history. The buyer detail also projects the viewings and offers the buyer is party to - read-only,
 * because those belong to the viewing and sales modules.</p>
 *
 * <p><b>The rule these records protect.</b> A brief's bounds are a range and must run forwards:
 * {@code minBudget <= maxBudget} and {@code minBedrooms <= maxBedrooms}. The schema carries both as
 * check constraints; the service refuses the bad range first, as a named {@code 400}, so the caller
 * learns which of the two is out of order rather than receiving an opaque integrity error. Every
 * bound is optional on purpose - "anywhere in Pune, two bedrooms or more" is a complete brief, and a
 * required budget would force a number the buyer never gave.</p>
 *
 * <p><b>Money is {@link BigDecimal} only.</b> No budget here is a {@code double}.</p>
 */
public final class BuyerDtos {

  private BuyerDtos() {}

  /** The closed set of buyer types the schema's {@code ck_buyers_type} allows. */
  private static final String BUYER_TYPE_SET =
      "INDIVIDUAL|COMPANY|NRI|TRUST|FUND|JOINT_VENTURE|OTHER";

  /** The closed set of buyer statuses the schema's {@code ck_buyers_status} allows. */
  private static final String BUYER_STATUS_SET = "ACTIVE|INACTIVE|CONVERTED";

  /** The closed set of purposes the schema's {@code ck_buyer_requirements_purpose} allows. */
  private static final String PURPOSE_SET = "OWN_OCCUPANCY|INVESTMENT|LETTING|REDEVELOPMENT";

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** One row of the buyer register. */
  public record BuyerSummary(
      UUID id,
      String buyerCode,
      String buyerType,
      String displayName,
      String primaryContactName,
      String email,
      String phone,
      String city,
      String state,
      String status,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static BuyerSummary from(Buyer buyer) {
      return new BuyerSummary(
          buyer.getId(),
          buyer.getBuyerCode(),
          name(buyer.getBuyerType()),
          buyer.getDisplayName(),
          buyer.getPrimaryContactName(),
          buyer.getEmail(),
          buyer.getPhone(),
          buyer.getCity(),
          buyer.getState(),
          name(buyer.getStatus()),
          buyer.getCreatedAt(),
          buyer.getUpdatedAt());
    }
  }

  /**
   * One buyer's full record, with their briefs, viewings and offers.
   *
   * <p>All three are read-only projections. A brief is written through the requirement endpoints;
   * a viewing and an offer are owned by their own modules and reported here only so the buyer page
   * can be drawn in one request.</p>
   */
  public record BuyerDetail(
      UUID id,
      String buyerCode,
      String buyerType,
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
      long requirementCount,
      long activeRequirementCount,
      List<RequirementRecord> requirements,
      List<ViewingRecord> viewings,
      List<OfferRecord> offers,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** One brief: what the buyer wants, where, and for how much. Every bound may be absent. */
  public record RequirementRecord(
      UUID requirementId,
      UUID propertyTypeId,
      String propertyTypeCode,
      String propertyTypeName,
      String preferredCity,
      String preferredLocality,
      BigDecimal minBudget,
      BigDecimal maxBudget,
      Integer minBedrooms,
      Integer maxBedrooms,
      BigDecimal minAreaSqft,
      String purpose,
      boolean active,
      String notes,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static RequirementRecord from(BuyerRequirement requirement) {
      var type = requirement.getPropertyType();
      return new RequirementRecord(
          requirement.getId(),
          type == null ? null : type.getId(),
          type == null ? null : type.getCode(),
          type == null ? null : type.getName(),
          requirement.getPreferredCity(),
          requirement.getPreferredLocality(),
          requirement.getMinBudget(),
          requirement.getMaxBudget(),
          requirement.getMinBedrooms(),
          requirement.getMaxBedrooms(),
          requirement.getMinAreaSqft(),
          name(requirement.getPurpose()),
          requirement.isActive(),
          requirement.getNotes(),
          requirement.getCreatedAt(),
          requirement.getUpdatedAt());
    }
  }

  /** A viewing this buyer attended or is booked for, as far as the buyer module may see it. */
  public record ViewingRecord(
      UUID viewingId,
      String viewingCode,
      String status,
      LocalDateTime scheduledAt,
      UUID propertyId,
      String propertyName,
      UUID unitId,
      Boolean interested) {

    public static ViewingRecord from(Viewing viewing) {
      var property = viewing.getProperty();
      var unit = viewing.getUnit();
      return new ViewingRecord(
          viewing.getId(),
          viewing.getViewingCode(),
          name(viewing.getStatus()),
          viewing.getScheduledAt(),
          property == null ? null : property.getId(),
          property == null ? null : property.getName(),
          unit == null ? null : unit.getId(),
          viewing.getInterested());
    }
  }

  /** An offer made on one of this buyer's sales, as far as the buyer module may see it. */
  public record OfferRecord(
      UUID offerId,
      UUID saleId,
      String saleCode,
      String offerNumber,
      BigDecimal amount,
      String status,
      String direction,
      LocalDateTime offeredAt,
      LocalDateTime respondedAt) {

    public static OfferRecord from(Offer offer, String saleCode) {
      return new OfferRecord(
          offer.getId(),
          offer.getSale() == null ? null : offer.getSale().getId(),
          saleCode,
          offer.getOfferNumber(),
          offer.getAmount(),
          name(offer.getStatus()),
          name(offer.getDirection()),
          offer.getOfferedAt(),
          offer.getRespondedAt());
    }
  }

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /** Add a buyer to the register. The buyer code is supplied by the caller (there is no series). */
  public record CreateBuyerRequest(
      @NotBlank
          @Pattern(regexp = BUYER_TYPE_SET, message = "must be a buyer type")
          String buyerType,
      @NotBlank @Size(max = 30) String buyerCode,
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
      @Pattern(regexp = BUYER_STATUS_SET, message = "must be a buyer status") String status,
      String notes) {}

  /** Change a buyer. Every field is optional; only the ones supplied are applied. */
  public record UpdateBuyerRequest(
      @Pattern(regexp = BUYER_TYPE_SET, message = "must be a buyer type") String buyerType,
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
      @Pattern(regexp = BUYER_STATUS_SET, message = "must be a buyer status") String status,
      String notes) {}

  /** State a brief for a buyer. Every bound is optional; a brief of "anywhere" is a real brief. */
  public record CreateRequirementRequest(
      UUID propertyTypeId,
      @Size(max = 120) String preferredCity,
      @Size(max = 120) String preferredLocality,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal minBudget,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal maxBudget,
      Integer minBedrooms,
      Integer maxBedrooms,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal minAreaSqft,
      @Pattern(regexp = PURPOSE_SET, message = "must be a requirement purpose") String purpose,
      Boolean active,
      @Size(max = 1000) String notes) {}

  /** Change a brief. Every field is optional; only the ones supplied are applied. */
  public record UpdateRequirementRequest(
      UUID propertyTypeId,
      @Size(max = 120) String preferredCity,
      @Size(max = 120) String preferredLocality,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal minBudget,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal maxBudget,
      Integer minBedrooms,
      Integer maxBedrooms,
      @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
          BigDecimal minAreaSqft,
      @Pattern(regexp = PURPOSE_SET, message = "must be a requirement purpose") String purpose,
      Boolean active,
      @Size(max = 1000) String notes) {}

  /** A brief's property type id, used by the service to resolve the association. */
  public static UUID propertyTypeIdOf(RequirementRecord requirement) {
    return requirement == null ? null : requirement.propertyTypeId();
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }
}
