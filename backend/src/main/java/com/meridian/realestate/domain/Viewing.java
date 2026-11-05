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
 * An appointment to inspect a specific property or unit, with a specific prospect, run by a specific
 * agent - the point at which an enquiry becomes a genuine intention.
 *
 * <p><strong>The outcome must be written whole.</strong> The {@code ck_viewings_outcome} constraint
 * refuses a half-applied outcome: a {@code COMPLETED} viewing must carry both {@code completedAt}
 * and {@code feedback}; a {@code CANCELLED} or {@code NO_SHOW} viewing must carry a
 * {@code cancelledReason} and must not carry a completion time; a {@code SCHEDULED} or
 * {@code CONFIRMED} viewing must not be completed. The lifecycle engine must therefore set the
 * {@code status} and the matching outcome columns together, in one statement, because any
 * intermediate state - for instance the status set first and the feedback written afterwards - is
 * rejected by the database.</p>
 *
 * <p>The prospect may be a lead, a buyer or a tenant, or a walk-in not yet any of the three; the
 * schema requires at least one of them. Feedback and the interested flag are recorded per viewing
 * rather than on the lead, because a prospect's reaction to one property says nothing about the
 * next.</p>
 */
@Entity
@Table(name = "viewings")
public class Viewing extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "viewing_code", nullable = false, length = 30, unique = true)
  private String viewingCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /** The unit being inspected, when the viewing is of a specific flat. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "unit_id")
  private Unit unit;

  /** The prospect, when they are already a lead. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lead_id")
  private Lead lead;

  /** The prospect, when they are a buyer. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "buyer_id")
  private Buyer buyer;

  /** The prospect, when they are a tenant. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tenant_id")
  private Tenant tenant;

  /** The prospect's own name, for a walk-in who is not yet any of the three. */
  @Column(name = "prospect_name", length = 200)
  private String prospectName;

  @Column(name = "prospect_phone", length = 30)
  private String prospectPhone;

  @Column(name = "prospect_email", length = 255)
  private String prospectEmail;

  /** The agent running the viewing. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "agent_id")
  private AgentProfile agent;

  @Column(name = "scheduled_at", nullable = false)
  private LocalDateTime scheduledAt;

  @Column(name = "duration_minutes", nullable = false)
  private int durationMinutes = 30;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ViewingStatus status = ViewingStatus.SCHEDULED;

  /** Required to complete a viewing; see the class note on the outcome constraint. */
  @Column(name = "feedback", columnDefinition = "text")
  private String feedback;

  @Column(name = "rating")
  private Integer rating;

  @Column(name = "interested")
  private Boolean interested;

  /** Required when the viewing is cancelled or a no-show; see the class note. */
  @Column(name = "cancelled_reason", length = 500)
  private String cancelledReason;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getViewingCode() {
    return viewingCode;
  }

  public void setViewingCode(String viewingCode) {
    this.viewingCode = viewingCode;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Unit getUnit() {
    return unit;
  }

  public void setUnit(Unit unit) {
    this.unit = unit;
  }

  public Lead getLead() {
    return lead;
  }

  public void setLead(Lead lead) {
    this.lead = lead;
  }

  public Buyer getBuyer() {
    return buyer;
  }

  public void setBuyer(Buyer buyer) {
    this.buyer = buyer;
  }

  public Tenant getTenant() {
    return tenant;
  }

  public void setTenant(Tenant tenant) {
    this.tenant = tenant;
  }

  public String getProspectName() {
    return prospectName;
  }

  public void setProspectName(String prospectName) {
    this.prospectName = prospectName;
  }

  public String getProspectPhone() {
    return prospectPhone;
  }

  public void setProspectPhone(String prospectPhone) {
    this.prospectPhone = prospectPhone;
  }

  public String getProspectEmail() {
    return prospectEmail;
  }

  public void setProspectEmail(String prospectEmail) {
    this.prospectEmail = prospectEmail;
  }

  public AgentProfile getAgent() {
    return agent;
  }

  public void setAgent(AgentProfile agent) {
    this.agent = agent;
  }

  public LocalDateTime getScheduledAt() {
    return scheduledAt;
  }

  public void setScheduledAt(LocalDateTime scheduledAt) {
    this.scheduledAt = scheduledAt;
  }

  public int getDurationMinutes() {
    return durationMinutes;
  }

  public void setDurationMinutes(int durationMinutes) {
    this.durationMinutes = durationMinutes;
  }

  public ViewingStatus getStatus() {
    return status;
  }

  public void setStatus(ViewingStatus status) {
    this.status = status;
  }

  public String getFeedback() {
    return feedback;
  }

  public void setFeedback(String feedback) {
    this.feedback = feedback;
  }

  public Integer getRating() {
    return rating;
  }

  public void setRating(Integer rating) {
    this.rating = rating;
  }

  public Boolean getInterested() {
    return interested;
  }

  public void setInterested(Boolean interested) {
    this.interested = interested;
  }

  public String getCancelledReason() {
    return cancelledReason;
  }

  public void setCancelledReason(String cancelledReason) {
    this.cancelledReason = cancelledReason;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(LocalDateTime completedAt) {
    this.completedAt = completedAt;
  }
}
