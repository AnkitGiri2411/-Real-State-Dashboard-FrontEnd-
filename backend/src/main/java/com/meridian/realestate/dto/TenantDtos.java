package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.MaintenanceRequest;
import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.Tenant;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The tenant register's API contract.
 *
 * <p><b>Why these records exist.</b> A {@code Tenant} entity never leaves the persistence layer.
 * Its register row, its full profile and the read-only projections of the leases, receipts and
 * faults it touches are each stated here as the deliberate shape a screen reads. The projections
 * are exactly that - projections of another module's records, not that module's own contract - so
 * they carry only the few columns a tenant page prints and point back at the owning record by id.</p>
 *
 * <p><b>The rule these records protect.</b> A tenant's annual income being absent is a real and
 * different state from an income of zero: {@code annualIncome} is nullable and bounded only to be
 * non-negative, because null means "not disclosed" and screening must be able to tell the two
 * apart rather than substituting a default. The email is declared with {@code @Email} and stored
 * lower-cased by the database ({@code ck_tenants_email_lower}), so a duplicate check and a lookup
 * agree on the same address.</p>
 *
 * <p><b>Money is {@link BigDecimal} only.</b> No income, rent or receipt here is a {@code double}.</p>
 */
public final class TenantDtos {

  private TenantDtos() {}

  /** The closed set of tenant types the schema's {@code ck_tenants_type} allows. */
  private static final String TENANT_TYPE_SET = "INDIVIDUAL|COMPANY|TRUST|GOVERNMENT|OTHER";

  /** The closed set of tenant statuses the schema's {@code ck_tenants_status} allows. */
  private static final String TENANT_STATUS_SET = "ACTIVE|INACTIVE|BLACKLISTED";

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** One row of the tenant register. */
  public record TenantSummary(
      UUID id,
      String tenantCode,
      String tenantType,
      String displayName,
      String primaryContactName,
      String email,
      String phone,
      String city,
      String state,
      String status,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static TenantSummary from(Tenant tenant) {
      return new TenantSummary(
          tenant.getId(),
          tenant.getTenantCode(),
          name(tenant.getTenantType()),
          tenant.getDisplayName(),
          tenant.getPrimaryContactName(),
          tenant.getEmail(),
          tenant.getPhone(),
          tenant.getCity(),
          tenant.getState(),
          name(tenant.getStatus()),
          tenant.getCreatedAt(),
          tenant.getUpdatedAt());
    }
  }

  /**
   * One tenant's full record, with the leases, receipts and faults behind it.
   *
   * <p>All three are read-only projections from other modules. The tenant module reports them so a
   * screen does not have to call three more endpoints to draw the page, but it never edits them -
   * the lease, payment and maintenance modules own their own records.</p>
   */
  public record TenantDetail(
      UUID id,
      String tenantCode,
      String tenantType,
      String displayName,
      String primaryContactName,
      String email,
      String phone,
      String alternatePhone,
      String idReference,
      String taxReference,
      String registrationNumber,
      String occupation,
      String employer,
      BigDecimal annualIncome,
      String addressLine1,
      String addressLine2,
      String locality,
      String city,
      String state,
      String postalCode,
      String country,
      String status,
      String notes,
      boolean runningLease,
      List<LeaseProjection> leases,
      List<PaymentProjection> payments,
      List<MaintenanceProjection> maintenance,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** A lease this tenant is a party to, as far as the tenant module may see it. */
  public record LeaseProjection(
      UUID leaseId,
      String leaseCode,
      String status,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal rentAmount,
      String rentFrequency,
      UUID unitId,
      UUID propertyId,
      String propertyName,
      boolean running) {

    public static LeaseProjection from(Lease lease) {
      var property = lease.getProperty();
      return new LeaseProjection(
          lease.getId(),
          lease.getLeaseCode(),
          name(lease.getStatus()),
          lease.getStartDate(),
          lease.getEndDate(),
          lease.getRentAmount(),
          name(lease.getRentFrequency()),
          lease.getUnitId(),
          property == null ? null : property.getId(),
          property == null ? null : property.getName(),
          lease.getStatus() != null && lease.getStatus().isRunning());
    }
  }

  /** A receipt from this tenant, as far as the tenant module may see it. */
  public record PaymentProjection(
      UUID paymentId,
      String receiptNumber,
      BigDecimal amount,
      String currency,
      String status,
      String direction,
      LocalDateTime receivedAt) {

    public static PaymentProjection from(Payment payment) {
      return new PaymentProjection(
          payment.getId(),
          payment.getReceiptNumber(),
          payment.getAmount(),
          payment.getCurrency(),
          name(payment.getStatus()),
          name(payment.getDirection()),
          payment.getReceivedAt());
    }
  }

  /** A fault reported by this tenant, as far as the tenant module may see it. */
  public record MaintenanceProjection(
      UUID requestId,
      String requestCode,
      String title,
      String category,
      String priority,
      String status,
      LocalDateTime reportedAt,
      boolean open) {

    public static MaintenanceProjection from(MaintenanceRequest request) {
      return new MaintenanceProjection(
          request.getId(),
          request.getRequestCode(),
          request.getTitle(),
          name(request.getCategory()),
          name(request.getPriority()),
          name(request.getStatus()),
          request.getReportedAt(),
          request.isOpen());
    }
  }

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /** Create a tenant profile. The tenant code is minted by the service, never supplied. */
  public record CreateTenantRequest(
      @NotBlank
          @Pattern(regexp = TENANT_TYPE_SET, message = "must be a tenant type")
          String tenantType,
      @NotBlank @Size(max = 200) String displayName,
      @Size(max = 200) String primaryContactName,
      @Email @Size(max = 255) String email,
      @Size(max = 30) String phone,
      @Size(max = 30) String alternatePhone,
      @Size(max = 60) String idReference,
      @Size(max = 60) String taxReference,
      @Size(max = 60) String registrationNumber,
      @Size(max = 120) String occupation,
      @Size(max = 200) String employer,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal annualIncome,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      @Pattern(regexp = TENANT_STATUS_SET, message = "must be a tenant status") String status,
      String notes) {}

  /** Change a tenant. Every field is optional; only the ones supplied are applied. */
  public record UpdateTenantRequest(
      @Pattern(regexp = TENANT_TYPE_SET, message = "must be a tenant type") String tenantType,
      @Size(max = 200) String displayName,
      @Size(max = 200) String primaryContactName,
      @Email @Size(max = 255) String email,
      @Size(max = 30) String phone,
      @Size(max = 30) String alternatePhone,
      @Size(max = 60) String idReference,
      @Size(max = 60) String taxReference,
      @Size(max = 60) String registrationNumber,
      @Size(max = 120) String occupation,
      @Size(max = 200) String employer,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal annualIncome,
      @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @Size(max = 120) String locality,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @Size(max = 20) String postalCode,
      @Size(max = 80) String country,
      @Pattern(regexp = TENANT_STATUS_SET, message = "must be a tenant status") String status,
      String notes) {}

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }
}
