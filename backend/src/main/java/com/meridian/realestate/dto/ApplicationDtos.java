package com.meridian.realestate.dto;

import com.meridian.realestate.domain.RentalApplication;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The rental-application API contracts.
 *
 * <p>An application is decided whole. {@link ApproveRequest} carries no decider and no decision time
 * - both are taken from the signed-in actor and the clock - and {@link RejectRequest} carries the
 * reason the schema requires on a rejection. Nothing here can half-apply a decision, because the
 * schema's {@code ck_rental_applications_decision} constraint refuses one.</p>
 *
 * <p>{@code outstandingChecks} on a summary is the count of screening rows still {@code PENDING}: the
 * figure that tells the desk whether a decision can be taken at all. A {@code WAIVED} check is a
 * finished check and is not counted.</p>
 */
public final class ApplicationDtos {

  private ApplicationDtos() {}

  /** A row in the applications register. */
  public record ApplicationSummary(
      UUID id,
      String applicationCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitNumber,
      UUID leadId,
      String leadName,
      UUID tenantId,
      String applicantType,
      String applicantName,
      String applicantEmail,
      String applicantPhone,
      String status,
      LocalDate proposedStartDate,
      LocalDate proposedEndDate,
      BigDecimal proposedRent,
      BigDecimal proposedDeposit,
      long outstandingChecks,
      LocalDateTime submittedAt,
      LocalDateTime decidedAt,
      LocalDateTime createdAt) {

    /** Must be called inside a transaction: the property, unit and lead are lazy. */
    public static ApplicationSummary from(RentalApplication application, long outstandingChecks) {
      return new ApplicationSummary(
          application.getId(),
          application.getApplicationCode(),
          application.getProperty() == null ? null : application.getProperty().getId(),
          application.getProperty() == null ? null : application.getProperty().getPropertyCode(),
          application.getProperty() == null ? null : application.getProperty().getName(),
          application.getUnit() == null ? null : application.getUnit().getId(),
          application.getUnit() == null ? null : application.getUnit().getUnitNumber(),
          application.getLead() == null ? null : application.getLead().getId(),
          application.getLead() == null ? null : application.getLead().getDisplayName(),
          application.getTenant() == null ? null : application.getTenant().getId(),
          application.getApplicantType() == null ? null : application.getApplicantType().name(),
          application.getApplicantName(),
          application.getApplicantEmail(),
          application.getApplicantPhone(),
          application.getStatus() == null ? null : application.getStatus().name(),
          application.getProposedStartDate(),
          application.getProposedEndDate(),
          application.getProposedRent(),
          application.getProposedDeposit(),
          outstandingChecks,
          application.getSubmittedAt(),
          application.getDecidedAt(),
          application.getCreatedAt());
    }
  }

  /** Everything the application screen shows for one application. */
  public record ApplicationDetail(
      UUID id,
      String applicationCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitNumber,
      UUID leadId,
      String leadName,
      UUID tenantId,
      String applicantType,
      String applicantName,
      String applicantEmail,
      String applicantPhone,
      String status,
      LocalDate proposedStartDate,
      LocalDate proposedEndDate,
      BigDecimal proposedRent,
      BigDecimal proposedDeposit,
      Integer occupancyCount,
      boolean hasPets,
      String employmentType,
      BigDecimal annualIncome,
      String notes,
      LocalDateTime submittedAt,
      LocalDateTime decidedAt,
      UUID decidedById,
      String decisionNotes,
      String rejectionReason,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long outstandingChecks,
      Set<String> allowedTransitions,
      List<ScreeningEntry> screenings,
      List<ApplicationStatusHistoryEntry> statusHistory) {}

  /** One screening check run on an application. */
  public record ScreeningEntry(
      UUID id,
      String checkType,
      String status,
      Integer score,
      String notes,
      String evidenceReference,
      UUID performedById,
      LocalDateTime performedAt) {

    public static ScreeningEntry from(
        com.meridian.realestate.domain.ApplicationScreening screening) {
      return new ScreeningEntry(
          screening.getId(),
          screening.getCheckType() == null ? null : screening.getCheckType().name(),
          screening.getStatus() == null ? null : screening.getStatus().name(),
          screening.getScore(),
          screening.getNotes(),
          screening.getEvidenceReference(),
          screening.getPerformedBy() == null ? null : screening.getPerformedBy().getId(),
          screening.getPerformedAt());
    }
  }

  /** One transition in an application's state. */
  public record ApplicationStatusHistoryEntry(
      UUID id,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedById,
      LocalDateTime changedAt) {

    public static ApplicationStatusHistoryEntry from(
        com.meridian.realestate.domain.ApplicationStatusHistory entry) {
      return new ApplicationStatusHistoryEntry(
          entry.getId(),
          entry.getFromStatus() == null ? null : entry.getFromStatus().name(),
          entry.getToStatus() == null ? null : entry.getToStatus().name(),
          entry.getReason(),
          entry.getChangedBy() == null ? null : entry.getChangedBy().getId(),
          entry.getChangedAt());
    }
  }

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /** Take a rental application for a specific unit. */
  public record CreateApplicationRequest(
      @NotNull UUID propertyId,
      @NotNull UUID unitId,
      UUID leadId,
      UUID tenantId,
      String applicantType,
      @NotBlank @Size(max = 200) String applicantName,
      @Email @Size(max = 255) String applicantEmail,
      @Size(max = 30) String applicantPhone,
      LocalDate proposedStartDate,
      LocalDate proposedEndDate,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal proposedRent,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal proposedDeposit,
      @Min(1) Integer occupancyCount,
      Boolean hasPets,
      String employmentType,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal annualIncome,
      String notes) {}

  /** Move an application into screening. */
  public record SubmitForScreeningRequest(@Size(max = 500) String reason) {}

  /**
   * Record the result of one screening check.
   *
   * <p>{@code WAIVED} is an explicit decision and records who made it and when, exactly as
   * {@code PASSED} and {@code FAILED} do - a waiver is not an absence of a check.</p>
   */
  public record RecordScreeningRequest(
      @NotBlank String checkType,
      @NotBlank String status,
      @Min(0) @Max(1000) Integer score,
      @Size(max = 1000) String notes,
      @Size(max = 255) String evidenceReference) {}

  /** Approve an application. The decider and the decision time are taken server-side. */
  public record ApproveRequest(@Size(max = 1000) String decisionNotes) {}

  /** Reject an application. The reason is required: a rejection records why. */
  public record RejectRequest(
      @NotBlank @Size(max = 500) String reason, @Size(max = 1000) String decisionNotes) {}

  /** Withdraw an application before a decision is taken. */
  public record WithdrawRequest(@Size(max = 500) String reason) {}
}
