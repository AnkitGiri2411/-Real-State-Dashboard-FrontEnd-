package com.meridian.realestate.dto;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.Lead;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The lead API contracts.
 *
 * <p>Note what is <b>not</b> here: a request that writes an arbitrary stage. A lead moves only
 * through {@code POST /leads/{id}/stage}, which validates the move against the service's transition
 * map, and only through the named operations for assignment, activities and follow-ups. That is why
 * {@link LeadDetail} publishes {@code allowedTransitions}: the interface can offer exactly the moves
 * that are legal right now rather than buttons the server will refuse.</p>
 *
 * <p>The pipeline and funnel shapes are read projections rather than rows, and they carry no
 * identifier: they summarise a set of leads by stage, which is what the dashboard actually draws.</p>
 */
public final class LeadDtos {

  private LeadDtos() {}

  /** The name a lead's holder reads back as, or null when it is unassigned or has no account. */
  static String agentName(AgentProfile agent) {
    if (agent == null || agent.getUser() == null) return null;
    return agent.getUser().getFullName();
  }

  /** A row in the lead register. */
  public record LeadSummary(
      UUID id,
      String leadCode,
      String displayName,
      String companyName,
      String leadType,
      String stage,
      String priority,
      String sourceCode,
      String sourceName,
      UUID ownerAgentId,
      String ownerAgentName,
      UUID officeId,
      String officeName,
      String email,
      String phone,
      BigDecimal expectedValue,
      String nextAction,
      LocalDateTime nextActionAt,
      /** True when the next action has come due and the lead is still being worked. */
      boolean overdue,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    /** Must be called inside a transaction: the source, holder and office are lazy. */
    public static LeadSummary from(Lead lead, LocalDateTime now) {
      LocalDateTime nextActionAt = lead.getNextActionAt();
      boolean overdue =
          nextActionAt != null
              && now != null
              && nextActionAt.isBefore(now)
              && lead.getStage() != null
              && lead.getStage().isOpen();
      return new LeadSummary(
          lead.getId(),
          lead.getLeadCode(),
          lead.getDisplayName(),
          lead.getCompanyName(),
          lead.getLeadType() == null ? null : lead.getLeadType().name(),
          lead.getStage() == null ? null : lead.getStage().name(),
          lead.getPriority() == null ? null : lead.getPriority().name(),
          lead.getSource() == null ? null : lead.getSource().getCode(),
          lead.getSource() == null ? null : lead.getSource().getName(),
          lead.getOwnerAgent() == null ? null : lead.getOwnerAgent().getId(),
          LeadDtos.agentName(lead.getOwnerAgent()),
          lead.getOffice() == null ? null : lead.getOffice().getId(),
          lead.getOffice() == null ? null : lead.getOffice().getName(),
          lead.getEmail(),
          lead.getPhone(),
          lead.getExpectedValue(),
          lead.getNextAction(),
          nextActionAt,
          overdue,
          lead.getCreatedAt(),
          lead.getUpdatedAt());
    }
  }

  /** Everything the lead screen shows for one lead. */
  public record LeadDetail(
      UUID id,
      String leadCode,
      String displayName,
      String companyName,
      String leadType,
      String stage,
      String priority,
      String sourceCode,
      String sourceName,
      UUID ownerAgentId,
      String ownerAgentName,
      UUID officeId,
      String officeName,
      String email,
      String phone,
      String alternatePhone,
      String propertyTypeCode,
      String preferredCity,
      String preferredLocality,
      BigDecimal minBudget,
      BigDecimal maxBudget,
      Integer minBedrooms,
      String requirementNotes,
      UUID propertyId,
      UUID unitId,
      String nextAction,
      LocalDateTime nextActionAt,
      BigDecimal expectedValue,
      LocalDateTime wonAt,
      LocalDateTime lostAt,
      String lostReason,
      LocalDateTime convertedAt,
      UUID convertedTenantId,
      UUID convertedBuyerId,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      /** The moves that are legal from the current stage, so the UI offers only those. */
      Set<String> allowedTransitions,
      List<LeadAssignmentEntry> assignments,
      List<LeadActivityEntry> activities,
      List<LeadFollowupEntry> followups,
      List<ViewingDtos.ViewingSummary> viewings,
      List<ApplicationDtos.ApplicationSummary> applications) {}

  /** One chapter in the history of who held a lead. */
  public record LeadAssignmentEntry(
      UUID id,
      UUID agentId,
      String agentName,
      LocalDateTime assignedAt,
      UUID assignedById,
      LocalDateTime unassignedAt,
      String reason) {

    public static LeadAssignmentEntry from(
        com.meridian.realestate.domain.LeadAssignment assignment) {
      return new LeadAssignmentEntry(
          assignment.getId(),
          assignment.getAgent() == null ? null : assignment.getAgent().getId(),
          LeadDtos.agentName(assignment.getAgent()),
          assignment.getAssignedAt(),
          assignment.getAssignedBy() == null ? null : assignment.getAssignedBy().getId(),
          assignment.getUnassignedAt(),
          assignment.getReason());
    }
  }

  /** A piece of work recorded against a lead. */
  public record LeadActivityEntry(
      UUID id,
      String activityType,
      String subject,
      String notes,
      LocalDateTime occurredAt,
      Integer durationMinutes,
      UUID performedById,
      String fromStage,
      String toStage) {

    public static LeadActivityEntry from(com.meridian.realestate.domain.LeadActivity activity) {
      return new LeadActivityEntry(
          activity.getId(),
          activity.getActivityType() == null ? null : activity.getActivityType().name(),
          activity.getSubject(),
          activity.getNotes(),
          activity.getOccurredAt(),
          activity.getDurationMinutes(),
          activity.getPerformedBy() == null ? null : activity.getPerformedBy().getId(),
          activity.getFromStage() == null ? null : activity.getFromStage().name(),
          activity.getToStage() == null ? null : activity.getToStage().name());
    }
  }

  /** A planned piece of work against a lead. */
  public record LeadFollowupEntry(
      UUID id,
      LocalDateTime dueAt,
      String channel,
      String notes,
      String status,
      UUID assignedToId,
      String assignedToName,
      LocalDateTime completedAt,
      String outcome,
      boolean overdue) {

    public static LeadFollowupEntry from(
        com.meridian.realestate.domain.LeadFollowup followup, LocalDateTime now) {
      boolean overdue =
          followup.getStatus() == com.meridian.realestate.domain.LeadFollowupStatus.PENDING
              && followup.getDueAt() != null
              && now != null
              && followup.getDueAt().isBefore(now);
      return new LeadFollowupEntry(
          followup.getId(),
          followup.getDueAt(),
          followup.getChannel() == null ? null : followup.getChannel().name(),
          followup.getNotes(),
          followup.getStatus() == null ? null : followup.getStatus().name(),
          followup.getAssignedTo() == null ? null : followup.getAssignedTo().getId(),
          LeadDtos.agentName(followup.getAssignedTo()),
          followup.getCompletedAt(),
          followup.getOutcome(),
          overdue);
    }
  }

  /** The pipeline: how many leads carry each stage and what the open pipeline is worth. */
  public record PipelineResponse(
      long totalLeads,
      long openLeads,
      BigDecimal openPipelineValue,
      LocalDateTime generatedAt,
      List<StageBucket> stages) {}

  /** One rung of the pipeline board: a stage, its count and its value. */
  public record StageBucket(String stage, long count, BigDecimal value, boolean open) {}

  /**
   * The conversion funnel.
   *
   * <p>Each rung is <b>cumulative</b>: it counts the leads that reached that stage or went past it,
   * so the counts can only fall as the funnel narrows. A per-stage count would not be monotonic and
   * would not describe a funnel at all.</p>
   */
  public record FunnelResponse(long totalLeads, List<FunnelRung> rungs, LocalDateTime generatedAt) {}

  /** One rung of the funnel: a stage and how many leads reached it. */
  public record FunnelRung(String stage, String label, long count) {}

  /** The "needs attention" rail: leads whose next action has come due, and overdue follow-ups. */
  public record NeedsAttentionResponse(
      int leadCount,
      int followupCount,
      List<LeadSummary> leads,
      List<LeadFollowupEntry> followups) {}

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /** Log a lead. A stage is never supplied: every lead starts at NEW. */
  public record CreateLeadRequest(
      String leadType,
      String sourceCode,
      String priority,
      @NotBlank @Size(max = 200) String displayName,
      @Size(max = 200) String companyName,
      @Email @Size(max = 255) String email,
      @Size(max = 30) String phone,
      @Size(max = 30) String alternatePhone,
      String propertyTypeCode,
      @Size(max = 120) String preferredCity,
      @Size(max = 120) String preferredLocality,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal minBudget,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal maxBudget,
      @Min(0) @Max(50) Integer minBedrooms,
      String requirementNotes,
      UUID propertyId,
      UUID unitId,
      UUID ownerAgentId,
      UUID officeId,
      @Size(max = 255) String nextAction,
      LocalDateTime nextActionAt,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal expectedValue) {}

  /** Change a lead's details. A null field is left unchanged; the stage has its own endpoint. */
  public record UpdateLeadRequest(
      String leadType,
      String sourceCode,
      String priority,
      @Size(max = 200) String displayName,
      @Size(max = 200) String companyName,
      @Email @Size(max = 255) String email,
      @Size(max = 30) String phone,
      @Size(max = 30) String alternatePhone,
      String propertyTypeCode,
      @Size(max = 120) String preferredCity,
      @Size(max = 120) String preferredLocality,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal minBudget,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal maxBudget,
      @Min(0) @Max(50) Integer minBedrooms,
      String requirementNotes,
      UUID propertyId,
      UUID unitId,
      UUID officeId,
      @Size(max = 255) String nextAction,
      LocalDateTime nextActionAt,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal expectedValue,
      @Size(max = 500) String lostReason) {}

  /** Move a lead between pipeline stages. */
  public record StageTransitionRequest(
      @NotBlank String stage,
      @Size(max = 500) String reason,
      /** Required when the target stage is LOST; refused otherwise. */
      @Size(max = 500) String lostReason) {}

  /** Assign or reassign the agent who holds a lead. */
  public record AssignLeadRequest(@NotNull UUID agentId, @Size(max = 500) String reason) {}

  /**
   * Convert a won lead into the tenant or the buyer it produced.
   *
   * <p>Exactly one of the two records is expected, and at least one is required: the schema's
   * {@code ck_leads_conversion} constraint refuses a conversion recorded with neither.</p>
   */
  public record ConvertLeadRequest(UUID tenantId, UUID buyerId, @Size(max = 500) String notes) {}

  /** Log work done against a lead. */
  public record LogActivityRequest(
      @NotBlank String activityType,
      @NotBlank @Size(max = 255) String subject,
      String notes,
      LocalDateTime occurredAt,
      @Min(0) Integer durationMinutes) {}

  /** Plan a follow-up against a lead. */
  public record LogFollowupRequest(
      @NotNull LocalDateTime dueAt,
      String channel,
      @Size(max = 1000) String notes,
      UUID assignedToId) {}

  /** Close a follow-up as done, cancelled or missed. */
  public record CompleteFollowupRequest(@NotBlank String status, @Size(max = 500) String outcome) {}
}
