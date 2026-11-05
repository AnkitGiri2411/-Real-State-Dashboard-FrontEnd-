package com.meridian.realestate.dto;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.Viewing;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The viewing API contracts.
 *
 * <p><b>The outcome is never a lone field.</b> A viewing moves through the named operations -
 * confirm, complete, cancel, no-show, reschedule - and each writes the status and the matching
 * outcome columns <em>together</em>, because the schema's {@code ck_viewings_outcome} constraint
 * refuses a half-applied outcome. Hence {@link CompleteViewingRequest#feedback} is required, and
 * {@link CancelViewingRequest#reason} is required: they are not optional annotations, they are the
 * other half of the state being written.</p>
 */
public final class ViewingDtos {

  private ViewingDtos() {}

  static String agentName(AgentProfile agent) {
    if (agent == null || agent.getUser() == null) return null;
    return agent.getUser().getFullName();
  }

  /** A row in the viewing diary. */
  public record ViewingSummary(
      UUID id,
      String viewingCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitNumber,
      UUID leadId,
      String leadName,
      UUID buyerId,
      String buyerName,
      UUID tenantId,
      String tenantName,
      String prospectName,
      String prospectPhone,
      UUID agentId,
      String agentName,
      LocalDateTime scheduledAt,
      int durationMinutes,
      String status,
      Integer rating,
      Boolean interested,
      LocalDateTime completedAt,
      LocalDateTime createdAt) {

    /** Must be called inside a transaction: the property, unit, prospect and agent are lazy. */
    public static ViewingSummary from(Viewing viewing) {
      return new ViewingSummary(
          viewing.getId(),
          viewing.getViewingCode(),
          viewing.getProperty() == null ? null : viewing.getProperty().getId(),
          viewing.getProperty() == null ? null : viewing.getProperty().getPropertyCode(),
          viewing.getProperty() == null ? null : viewing.getProperty().getName(),
          viewing.getUnit() == null ? null : viewing.getUnit().getId(),
          viewing.getUnit() == null ? null : viewing.getUnit().getUnitNumber(),
          viewing.getLead() == null ? null : viewing.getLead().getId(),
          viewing.getLead() == null ? null : viewing.getLead().getDisplayName(),
          viewing.getBuyer() == null ? null : viewing.getBuyer().getId(),
          viewing.getBuyer() == null ? null : viewing.getBuyer().getDisplayName(),
          viewing.getTenant() == null ? null : viewing.getTenant().getId(),
          viewing.getTenant() == null ? null : viewing.getTenant().getDisplayName(),
          viewing.getProspectName(),
          viewing.getProspectPhone(),
          viewing.getAgent() == null ? null : viewing.getAgent().getId(),
          ViewingDtos.agentName(viewing.getAgent()),
          viewing.getScheduledAt(),
          viewing.getDurationMinutes(),
          viewing.getStatus() == null ? null : viewing.getStatus().name(),
          viewing.getRating(),
          viewing.getInterested(),
          viewing.getCompletedAt(),
          viewing.getCreatedAt());
    }
  }

  /** Everything the viewing screen shows for one appointment. */
  public record ViewingDetail(
      UUID id,
      String viewingCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitNumber,
      UUID leadId,
      String leadName,
      UUID buyerId,
      String buyerName,
      UUID tenantId,
      String tenantName,
      String prospectName,
      String prospectPhone,
      String prospectEmail,
      UUID agentId,
      String agentName,
      LocalDateTime scheduledAt,
      int durationMinutes,
      String status,
      String feedback,
      Integer rating,
      Boolean interested,
      String cancelledReason,
      LocalDateTime completedAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      Set<String> allowedTransitions,
      List<ViewingStatusHistoryEntry> statusHistory) {}

  /** One transition in a viewing's state. */
  public record ViewingStatusHistoryEntry(
      UUID id,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedById,
      LocalDateTime changedAt) {

    public static ViewingStatusHistoryEntry from(
        com.meridian.realestate.domain.ViewingStatusHistory entry) {
      return new ViewingStatusHistoryEntry(
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

  /** Book a viewing. The prospect must be named by one of the linked records or free text. */
  public record ScheduleViewingRequest(
      @NotNull UUID propertyId,
      UUID unitId,
      UUID leadId,
      UUID buyerId,
      UUID tenantId,
      @Size(max = 200) String prospectName,
      @Size(max = 30) String prospectPhone,
      @Size(max = 255) String prospectEmail,
      UUID agentId,
      @NotNull LocalDateTime scheduledAt,
      @Min(1) @Max(1440) Integer durationMinutes) {}

  /** Move a booked viewing to a new time, or change who runs it. */
  public record RescheduleViewingRequest(
      @NotNull LocalDateTime scheduledAt,
      @Min(1) @Max(1440) Integer durationMinutes,
      UUID agentId,
      @Size(max = 200) String prospectName,
      @Size(max = 30) String prospectPhone) {}

  /**
   * Complete a viewing with its outcome.
   *
   * <p>{@code feedback} is required: a completed viewing with no reaction recorded is a lost
   * opportunity rather than a result, and the database refuses the row without it.</p>
   */
  public record CompleteViewingRequest(
      @NotBlank String feedback,
      @Min(1) @Max(5) Integer rating,
      Boolean interested,
      LocalDateTime completedAt) {}

  /** Cancel a viewing. The reason is required: a cancelled viewing records why. */
  public record CancelViewingRequest(@NotBlank @Size(max = 500) String reason) {}

  /** Mark a viewing as a no-show. The reason is required. */
  public record NoShowRequest(@NotBlank @Size(max = 500) String reason) {}
}
