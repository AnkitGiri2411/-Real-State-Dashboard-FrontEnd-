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
 * The record of work done on a lead - the timeline a reader scrolls to understand it.
 *
 * <p>A stage move is an activity like any other, and carries {@code from_stage} / {@code to_stage}
 * so the timeline shows <em>why</em> a lead advanced rather than merely that it did.</p>
 */
@Entity
@Table(name = "lead_activities")
public class LeadActivity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lead_id", nullable = false)
  private Lead lead;

  @Enumerated(EnumType.STRING)
  @Column(name = "activity_type", nullable = false, length = 30)
  private LeadActivityType activityType;

  @Column(name = "subject", nullable = false, length = 255)
  private String subject;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  @Column(name = "duration_minutes")
  private Integer durationMinutes;

  /** Who did it. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "performed_by")
  private User performedBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_stage", length = 30)
  private LeadStage fromStage;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_stage", length = 30)
  private LeadStage toStage;

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

  public LeadActivityType getActivityType() {
    return activityType;
  }

  public void setActivityType(LeadActivityType activityType) {
    this.activityType = activityType;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public LocalDateTime getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(LocalDateTime occurredAt) {
    this.occurredAt = occurredAt;
  }

  public Integer getDurationMinutes() {
    return durationMinutes;
  }

  public void setDurationMinutes(Integer durationMinutes) {
    this.durationMinutes = durationMinutes;
  }

  public User getPerformedBy() {
    return performedBy;
  }

  public void setPerformedBy(User performedBy) {
    this.performedBy = performedBy;
  }

  public LeadStage getFromStage() {
    return fromStage;
  }

  public void setFromStage(LeadStage fromStage) {
    this.fromStage = fromStage;
  }

  public LeadStage getToStage() {
    return toStage;
  }

  public void setToStage(LeadStage toStage) {
    this.toStage = toStage;
  }
}
