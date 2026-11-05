package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Vendor;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The vendor register API contracts.
 *
 * <p>A vendor is one of the empanelled trades the firm calls out to do work, so the register is a
 * read of the approved list rather than a party the desk negotiates with: the shape here carries the
 * trade, the contact block and the two facts that decide whether work may be handed over -
 * {@code empanelled} and {@code status}.</p>
 *
 * <p>{@code assignable} is published on both the row and the detail because it is the answer to the
 * only question the assignment screen asks ("may this vendor be given a plumbing fault?"), and
 * because it is not the same as being on the approved list: a suspended vendor is empanelled and may
 * still not be given new work. Deriving that on the client would let two screens disagree about a
 * vendor, so the service states it once.</p>
 *
 * <p>Nothing here writes {@code vendorCode}: a code is minted from the module's business-number
 * series when the vendor is created, so a caller cannot invent one that collides with a record the
 * desk already has on paper.</p>
 */
public final class VendorDtos {

  private VendorDtos() {}

  /** A row in the vendor register. */
  public record VendorSummary(
      UUID id,
      String vendorCode,
      String name,
      String category,
      String contactName,
      String phone,
      String email,
      String city,
      boolean empanelled,
      Integer rating,
      Integer paymentTermsDays,
      String status,
      /** True when the vendor may be given new work: empanelled is not enough on its own. */
      boolean assignable,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static VendorSummary from(Vendor vendor) {
      return new VendorSummary(
          vendor.getId(),
          vendor.getVendorCode(),
          vendor.getName(),
          vendor.getCategory() == null ? null : vendor.getCategory().name(),
          vendor.getContactName(),
          vendor.getPhone(),
          vendor.getEmail(),
          vendor.getCity(),
          vendor.isEmpanelled(),
          vendor.getRating(),
          vendor.getPaymentTermsDays(),
          vendor.getStatus() == null ? null : vendor.getStatus().name(),
          vendor.isAssignable(),
          vendor.getCreatedAt(),
          vendor.getUpdatedAt());
    }
  }

  /** Everything the vendor screen shows for one vendor. */
  public record VendorDetail(
      UUID id,
      String vendorCode,
      String name,
      String category,
      String contactName,
      String phone,
      String alternatePhone,
      String email,
      String taxReference,
      String addressLine1,
      String addressLine2,
      String locality,
      String city,
      String state,
      String postalCode,
      String country,
      boolean empanelled,
      Integer rating,
      Integer paymentTermsDays,
      String status,
      boolean assignable,
      String notes,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static VendorDetail from(Vendor vendor) {
      return new VendorDetail(
          vendor.getId(),
          vendor.getVendorCode(),
          vendor.getName(),
          vendor.getCategory() == null ? null : vendor.getCategory().name(),
          vendor.getContactName(),
          vendor.getPhone(),
          vendor.getAlternatePhone(),
          vendor.getEmail(),
          vendor.getTaxReference(),
          vendor.getAddressLine1(),
          vendor.getAddressLine2(),
          vendor.getLocality(),
          vendor.getCity(),
          vendor.getState(),
          vendor.getPostalCode(),
          vendor.getCountry(),
          vendor.isEmpanelled(),
          vendor.getRating(),
          vendor.getPaymentTermsDays(),
          vendor.getStatus() == null ? null : vendor.getStatus().name(),
          vendor.isAssignable(),
          vendor.getNotes(),
          vendor.getCreatedAt(),
          vendor.getUpdatedAt());
    }
  }

  /** Add a trade to the register. The code is minted, never supplied. */
  public record CreateVendorRequest(
      @NotBlank @Size(max = 200) String name,
      /** One of the values {@code ck_vendors_category} allows. */
      @NotBlank String category,
      @Size(max = 200) String contactName,
      @Size(max = 30) String phone,
      @Size(max = 30) String alternatePhone,
      @Email @Size(max = 255) String email,
      @Size(max = 60) String taxReference,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      /** Defaults to true: a vendor is added to the approved list, not to a holding pen. */
      Boolean empanelled,
      @Min(1) @Max(5) Integer rating,
      @Min(0) @Max(365) Integer paymentTermsDays,
      String status,
      String notes) {}

  /** Change a vendor's details. A null field is left unchanged. */
  public record UpdateVendorRequest(
      @Size(max = 200) String name,
      String category,
      @Size(max = 200) String contactName,
      @Size(max = 30) String phone,
      @Size(max = 30) String alternatePhone,
      @Email @Size(max = 255) String email,
      @Size(max = 60) String taxReference,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      Boolean empanelled,
      @Min(1) @Max(5) Integer rating,
      @Min(0) @Max(365) Integer paymentTermsDays,
      String status,
      String notes) {}
}
