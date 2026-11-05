package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One chapter in the history of who held a lead.
 *
 * <p>{@code leads.owner_agent_id} holds the current holder so the pipeline board can read it without
 * joining this table; this table holds the history so "who had this lead in March" stays
 * answerable. The schema allows at most one open assignment per lead.</p>
 */
@Entity
@Table(name = "lead_assignments")
public class LeadAssignment extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lead_id", nullable = false)
  private Lead lead;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "agent_id", nullable = false)
  private AgentProfile agent;

  @Column(name = "assigned_at", nullable = false)
  private LocalDateTime assignedAt;

  /** Who made the assignment. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "assigned_by")
  private User assignedBy;

  @Column(name = "unassigned_at")
  private LocalDateTime unassignedAt;

  @Column(name = "reason", length = 500)
  private String reason;

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

  public AgentProfile getAgent() {
    return agent;
  }

  public void setAgent(AgentProfile agent) {
    this.agent = agent;
  }

  public LocalDateTime getAssignedAt() {
    return assignedAt;
  }

  public void setAssignedAt(LocalDateTime assignedAt) {
    this.assignedAt = assignedAt;
  }

  public User getAssignedBy() {
    return assignedBy;
  }

  public void setAssignedBy(User assignedBy) {
    this.assignedBy = assignedBy;
  }

  public LocalDateTime getUnassignedAt() {
    return unassignedAt;
  }

  public void setUnassignedAt(LocalDateTime unassignedAt) {
    this.unassignedAt = unassignedAt;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
