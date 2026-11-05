package com.meridian.realestate.dto;

import com.meridian.realestate.domain.MaintenanceRequest;
import com.meridian.realestate.domain.MaintenanceStatus;
import jakarta.validation.constraints.DecimalMin;
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
 * The maintenance API contracts.
 *
 * <p>Note what is <b>not</b> here, and why. There is no request that writes a status from a field
 * form: a request moves only through {@code POST /maintenance-requests/{id}/status}, which validates
 * the move against the service's transition map, and resolution has its own endpoint because a
 * resolved request carries two facts the schema insists on together - the moment it was fixed and
 * what was done about it - plus, in the ordinary case, what it cost. {@link MaintenanceDetail}
 * therefore publishes {@code allowedTransitions} so a screen offers exactly the moves that are legal
 * right now.</p>
 *
 * <p>Cost appears twice on purpose. {@code estimatedCost} is what the fault was expected to cost and
 * {@code actualCost} is what it did; the two are not a duplication and are deliberately not forced
 * equal, because the variance between them is what the operations review argues about.</p>
 *
 * <p><b>{@code late} and {@code overdue}.</b> {@code late} is the product's own threshold: a request
 * nobody has answered - still {@code OPEN}, which is precisely "no assignee" - more than
 * {@code app.portfolio.maintenance-urgent-hours} after it was reported. {@code overdue} is the wider
 * flag the board filters on: late, or past its own target date. Both are computed in the projection
 * so the register, the detail and the summary cannot disagree about which requests need attention.
 * The rule is stated once, here, and the service passes its configured threshold in.</p>
 */
public final class MaintenanceDtos {

  private MaintenanceDtos() {}

  /**
   * True when a request has waited past the configured threshold without being answered.
   *
   * <p>"Unanswered" is the schema's own encoding of it: a request that still carries no assignee is
   * {@code OPEN} - {@code ck_maintenance_requests_assigned} refuses {@code ASSIGNED} without a
   * holder - so the status alone answers the question, and no second flag can contradict it.</p>
   */
  public static boolean isLate(MaintenanceRequest request, LocalDateTime now, int urgentHours) {
    if (request == null || now == null) return false;
    if (request.getStatus() != MaintenanceStatus.OPEN) return false;
    LocalDateTime reportedAt = request.getReportedAt();
    if (reportedAt == null) return false;
    return reportedAt.plusHours(Math.max(urgentHours, 0)).isBefore(now);
  }

  /** True when the request is still outstanding and has passed its target date, or is late. */
  public static boolean isOverdue(MaintenanceRequest request, LocalDateTime now, int urgentHours) {
    if (request == null || !request.isOpen()) return false;
    if (isLate(request, now, urgentHours)) return true;
    LocalDate target = request.getTargetDate();
    return target != null && now != null && target.isBefore(now.toLocalDate());
  }

  /** A row on the maintenance board. */
  public record MaintenanceSummary(
      UUID id,
      String requestCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitCode,
      UUID buildingId,
      String category,
      String priority,
      String status,
      String title,
      String reporterName,
      UUID assignedVendorId,
      String assignedVendorName,
      UUID assignedToId,
      String assignedToName,
      LocalDateTime reportedAt,
      LocalDate targetDate,
      LocalDateTime assignedAt,
      LocalDateTime startedAt,
      LocalDateTime resolvedAt,
      LocalDateTime closedAt,
      BigDecimal estimatedCost,
      BigDecimal actualCost,
      boolean chargeableToTenant,
      /** Nobody has answered it, past the configured threshold. */
      boolean late,
      /** Still outstanding and late, or past its own target date. */
      boolean overdue,
      boolean open,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    /** Must be called inside a transaction: the property, unit, vendor and assignee are lazy. */
    public static MaintenanceSummary from(
        MaintenanceRequest request, LocalDateTime now, int urgentHours) {
      return new MaintenanceSummary(
          request.getId(),
          request.getRequestCode(),
          request.getProperty() == null ? null : request.getProperty().getId(),
          request.getProperty() == null ? null : request.getProperty().getPropertyCode(),
          request.getProperty() == null ? null : request.getProperty().getName(),
          request.getUnit() == null ? null : request.getUnit().getId(),
          request.getUnit() == null ? null : request.getUnit().getUnitCode(),
          request.getBuilding() == null ? null : request.getBuilding().getId(),
          request.getCategory() == null ? null : request.getCategory().name(),
          request.getPriority() == null ? null : request.getPriority().name(),
          request.getStatus() == null ? null : request.getStatus().name(),
          request.getTitle(),
          request.getReporterName(),
          request.getAssignedVendor() == null ? null : request.getAssignedVendor().getId(),
          request.getAssignedVendor() == null ? null : request.getAssignedVendor().getName(),
          request.getAssignedTo() == null ? null : request.getAssignedTo().getId(),
          request.getAssignedTo() == null ? null : request.getAssignedTo().getFullName(),
          request.getReportedAt(),
          request.getTargetDate(),
          request.getAssignedAt(),
          request.getStartedAt(),
          request.getResolvedAt(),
          request.getClosedAt(),
          request.getEstimatedCost(),
          request.getActualCost(),
          request.isChargeableToTenant(),
          isLate(request, now, urgentHours),
          isOverdue(request, now, urgentHours),
          request.isOpen(),
          request.getCreatedAt(),
          request.getUpdatedAt());
    }
  }

  /** Everything the fault screen shows for one request, including its status trail. */
  public record MaintenanceDetail(
      UUID id,
      String requestCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitCode,
      UUID buildingId,
      String category,
      String priority,
      String status,
      String title,
      String description,
      UUID reportedById,
      String reportedByName,
      UUID reportedByTenantId,
      String reporterName,
      UUID assignedVendorId,
      String assignedVendorName,
      UUID assignedToId,
      String assignedToName,
      LocalDateTime reportedAt,
      LocalDate targetDate,
      LocalDateTime assignedAt,
      LocalDateTime startedAt,
      LocalDateTime resolvedAt,
      LocalDateTime closedAt,
      BigDecimal estimatedCost,
      BigDecimal actualCost,
      String resolutionNotes,
      boolean chargeableToTenant,
      boolean late,
      boolean overdue,
      boolean open,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      /** The moves that are legal from the current status, so the UI offers only those. */
      Set<String> allowedTransitions,
      List<StatusHistoryEntry> statusHistory) {}

  /** One recorded transition in a request's status. */
  public record StatusHistoryEntry(
      UUID id,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedById,
      LocalDateTime changedAt) {}

  /** The operations card: how much work is outstanding and what it has cost. */
  public record MaintenanceSummaryResponse(
      long openCount,
      long urgentOpenCount,
      /** Still unanswered past the configured threshold - the row the reader must act on. */
      long lateOpenCount,
      BigDecimal costToDate,
      BigDecimal estimatedOpenCost,
      LocalDateTime generatedAt,
      List<StatusBucket> byStatus,
      List<PriorityBucket> byPriority) {}

  /** One column of the board: a status and how many requests sit in it. */
  public record StatusBucket(String status, String label, long count, boolean open) {}

  /** One bar of the priority chart. */
  public record PriorityBucket(String priority, long count, boolean urgent) {}

  /** What maintenance has cost, month by month. */
  public record CostTrendResponse(
      LocalDate from,
      LocalDate to,
      BigDecimal totalCost,
      long resolvedCount,
      List<CostTrendPoint> months,
      LocalDateTime generatedAt) {}

  /** One month of the maintenance cost trend. */
  public record CostTrendPoint(
      int year, int month, String label, BigDecimal actualCost, long resolvedCount) {}

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /** Raise a request. It always starts at {@code OPEN}, whatever a caller may have hoped. */
  public record CreateMaintenanceRequest(
      @NotBlank String category,
      String priority,
      @NotBlank @Size(max = 200) String title,
      String description,
      @NotNull UUID propertyId,
      UUID unitId,
      UUID buildingId,
      UUID reportedByTenantId,
      @Size(max = 200) String reporterName,
      LocalDate targetDate,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal estimatedCost,
      Boolean chargeableToTenant) {}

  /** Change a request's details. A null field is left unchanged; the status has its own endpoint. */
  public record UpdateMaintenanceRequest(
      String category,
      String priority,
      @Size(max = 200) String title,
      String description,
      UUID unitId,
      UUID buildingId,
      UUID reportedByTenantId,
      @Size(max = 200) String reporterName,
      LocalDate targetDate,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal estimatedCost,
      Boolean chargeableToTenant) {}

  /**
   * Hand the work to somebody.
   *
   * <p>Exactly one holder is expected - a vendor or a colleague - and at least one is required: a
   * request that claims to be assigned to nobody is how work silently stops, which is the state the
   * schema's own constraint exists to forbid.</p>
   */
  public record AssignMaintenanceRequest(
      UUID vendorId, UUID assignedToId, @Size(max = 500) String reason) {}

  /** Move a request between statuses. Resolution has its own endpoint and is refused here. */
  public record StatusTransitionRequest(@NotBlank String status, @Size(max = 500) String reason) {}

  /**
   * Record what was done about a fault and what it cost.
   *
   * <p>{@code actualCost} is nullable because a fault can be fixed by the firm's own team at no
   * recorded cost, and a zero would be a claim the ledger cannot support. When it is given it is
   * stored as the request's actual cost and is deliberately <em>not</em> reconciled against the
   * estimate: the estimate is what the approval conversation was about, and the variance between the
   * two is a fact the operations review reads.</p>
   */
  public record ResolveMaintenanceRequest(
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal actualCost,
      @NotBlank @Size(max = 2000) String resolutionNotes,
      @Size(max = 500) String reason) {}
}
