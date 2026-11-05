package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A planned piece of work against a lead - the diary.
 *
 * <p>The schema ties {@code completed_at} to {@link LeadFollowupStatus}: only {@code DONE} may carry
 * it, which is what lets the overdue calculation trust that a pending row has no completion
 * time.</p>
 */
@Entity
@Table(name = "lead_followups")
public class LeadFollowup extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lead_id", nullable = false)
  private Lead lead;

  @Column(name = "due_at", nullable = false)
  private LocalDateTime dueAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false, length = 30)
  private LeadFollowupChannel channel = LeadFollowupChannel.CALL;

  @Column(name = "notes", length = 1000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private LeadFollowupStatus status = LeadFollowupStatus.PENDING;

  /** The agent to whom the follow-up is assigned. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "assigned_to")
  private AgentProfile assignedTo;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Column(name = "outcome", length = 500)
  private String outcome;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Lead getLead() {
    return lead;
  }

  public void setLead(Lead lead) {
    this.lead = lead;
  }

  public LocalDateTime getDueAt() {
    return dueAt;
  }

  public void setDueAt(LocalDateTime dueAt) {
    this.dueAt = dueAt;
  }

  public LeadFollowupChannel getChannel() {
    return channel;
  }

  public void setChannel(LeadFollowupChannel channel) {
    this.channel = channel;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public LeadFollowupStatus getStatus() {
    return status;
  }

  public void setStatus(LeadFollowupStatus status) {
    this.status = status;
  }

  public AgentProfile getAssignedTo() {
    return assignedTo;
  }

  public void setAssignedTo(AgentProfile assignedTo) {
    this.assignedTo = assignedTo;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(LocalDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }
}
