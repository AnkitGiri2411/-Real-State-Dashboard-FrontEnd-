package com.meridian.realestate.dto;

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
 * The lease API's contracts: drafts, the rent schedule, the status trail, renewals and the
 * expiry report.
 *
 * <p>Money appears only as {@link BigDecimal} - the headline rent, a term's rent, a deposit, a
 * renewal's proposed rent. Nothing here is a {@code double} or a {@code float}, and no field
 * carries a credential: a lease payload has nowhere to put a password hash, so one cannot be
 * leaked by accident.</p>
 *
 * <p>The read models carry the identifiers of the slices that are not part of this module (the
 * unit, the tenant, the owner) as raw {@code UUID}s rather than half-built object graphs, and
 * carry the resolved names alongside them because a letting desk reads a lease by the property
 * and the tenant, never by a UUID.</p>
 */
public final class LeaseDtos {

  private LeaseDtos() {}

  // ---------------------------------------------------------------------------
  // Read models
  // ---------------------------------------------------------------------------

  /** A row in the lease register. */
  public record LeaseSummary(
      UUID id,
      String leaseCode,
      String status,
      UUID propertyId,
      String propertyName,
      UUID unitId,
      UUID tenantId,
      String tenantName,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal rentAmount,
      String rentFrequency,
      int dueDay,
      BigDecimal depositAmount,
      LocalDateTime createdAt) {}

  /** One step in a lease's rent schedule. */
  public record LeaseTermView(
      UUID id,
      int termNumber,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      BigDecimal rentAmount,
      BigDecimal escalationPercent,
      String notes) {}

  /** One recorded status transition. */
  public record StatusChangeView(
      UUID id,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedBy,
      LocalDateTime changedAt) {}

  /** A renewal conversation and its outcome. */
  public record RenewalView(
      UUID id,
      UUID leaseId,
      UUID renewedLeaseId,
      String status,
      LocalDateTime proposedAt,
      BigDecimal proposedRent,
      LocalDate proposedStartDate,
      LocalDate proposedEndDate,
      LocalDateTime decidedAt,
      UUID decidedBy,
      String decisionNotes) {}

  /**
   * The lease's money position, every figure derived from the invoices and receipts that point at
   * it - nothing here is stored on the lease.
   *
   * @param invoiced what the lease has been billed
   * @param settled what has actually been collected against it (allocations on {@code PAID}
   *     receipts, voided invoices excluded)
   * @param outstanding what is still owed, {@code total - credited - settled} rolled up
   * @param overdue the part of {@code outstanding} that is past its due date plus the configured
   *     grace period
   */
  public record LeaseFinance(
      BigDecimal invoiced,
      BigDecimal settled,
      BigDecimal outstanding,
      BigDecimal overdue,
      long invoiceCount,
      long overdueCount,
      long receiptCount) {}

  /** Everything the lease detail screen reads. */
  public record LeaseDetail(
      UUID id,
      String leaseCode,
      String status,
      UUID propertyId,
      String propertyName,
      UUID unitId,
      UUID tenantId,
      String tenantName,
      UUID primaryOwnerId,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal rentAmount,
      String rentFrequency,
      int dueDay,
      BigDecimal escalationPercent,
      BigDecimal depositAmount,
      LocalDateTime depositReceivedAt,
      BigDecimal depositRefundedAmount,
      LocalDateTime depositRefundedAt,
      int noticePeriodDays,
      String agreementReference,
      LocalDateTime signedAt,
      LocalDateTime activatedAt,
      LocalDateTime terminatedAt,
      String terminationReason,
      UUID renewalOfLeaseId,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      List<LeaseTermView> terms,
      List<StatusChangeView> statusHistory,
      List<RenewalView> renewals,
      LeaseFinance finance) {}

  /** One lease due to end inside the configured expiry window. */
  public record ExpiringLease(
      UUID id,
      String leaseCode,
      String status,
      UUID propertyId,
      String propertyName,
      UUID unitId,
      UUID tenantId,
      String tenantName,
      LocalDate endDate,
      long daysUntilEnd,
      String urgency,
      BigDecimal rentAmount) {}

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /** Draft a lease. The lease number is minted server-side; the status starts at {@code DRAFT}. */
  public record CreateLeaseRequest(
      UUID propertyId,
      @NotNull(message = "A unit is required") UUID unitId,
      @NotNull(message = "A tenant is required") UUID tenantId,
      UUID primaryOwnerId,
      @NotNull(message = "A start date is required") LocalDate startDate,
      @NotNull(message = "An end date is required") LocalDate endDate,
      @NotNull(message = "A rent amount is required")
          @DecimalMin(value = "0.01", message = "must be greater than zero")
          BigDecimal rentAmount,
      @Pattern(regexp = "MONTHLY|QUARTERLY|HALF_YEARLY|ANNUAL", message = "must be a rent frequency")
          String rentFrequency,
      @Min(value = 1, message = "must be between 1 and 28")
          @Max(value = 28, message = "must be between 1 and 28")
          Integer dueDay,
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal escalationPercent,
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal depositAmount,
      @Min(value = 0, message = "must be between 0 and 365")
          @Max(value = 365, message = "must be between 0 and 365")
          Integer noticePeriodDays,
      @Size(max = 120) String agreementReference) {}

  /** Change a lease's terms. Every field is optional; only what is sent is applied. */
  public record UpdateLeaseRequest(
      LocalDate startDate,
      LocalDate endDate,
      @DecimalMin(value = "0.01", message = "must be greater than zero") BigDecimal rentAmount,
      @Pattern(regexp = "MONTHLY|QUARTERLY|HALF_YEARLY|ANNUAL", message = "must be a rent frequency")
          String rentFrequency,
      @Min(value = 1, message = "must be between 1 and 28")
          @Max(value = 28, message = "must be between 1 and 28")
          Integer dueDay,
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal escalationPercent,
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal depositAmount,
      @Min(value = 0, message = "must be between 0 and 365")
          @Max(value = 365, message = "must be between 0 and 365")
          Integer noticePeriodDays,
      @Size(max = 120) String agreementReference,
      UUID primaryOwnerId) {}

  /** Move a lease between lifecycle states. */
  public record ChangeStatusRequest(
      @NotBlank(message = "A target status is required") String status,
      @Size(max = 500) String reason) {}

  /** End a lease. The reason is recorded on the lease and in the trail. */
  public record TerminateRequest(
      @NotBlank(message = "A termination reason is required") @Size(max = 500) String reason) {}

  /** Add a step to a lease's rent schedule. */
  public record CreateTermRequest(
      @NotNull(message = "An effective-from date is required") LocalDate effectiveFrom,
      LocalDate effectiveTo,
      @NotNull(message = "A rent amount is required")
          @DecimalMin(value = "0.01", message = "must be greater than zero")
          BigDecimal rentAmount,
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal escalationPercent,
      @Size(max = 500) String notes) {}

  /** Open a renewal conversation, or put an offer to the tenant. */
  public record ProposeRenewalRequest(
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal proposedRent,
      LocalDate proposedStartDate,
      LocalDate proposedEndDate) {}

  /** The terms of a renewal offer. */
  public record OfferRenewalRequest(
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal proposedRent,
      LocalDate proposedStartDate,
      LocalDate proposedEndDate) {}

  /** Accept a renewal: this is what drafts the new lease and moves the old one to {@code RENEWED}. */
  public record DecideRenewalRequest(
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal agreedRent,
      LocalDate startDate,
      LocalDate endDate,
      @Size(max = 1000) String notes) {}

  /** Decline a renewal, with the reason recorded against the decision. */
  public record DeclineRenewalRequest(@Size(max = 1000) String notes) {}
}
