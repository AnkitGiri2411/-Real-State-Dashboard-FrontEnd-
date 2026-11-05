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
 * How an asset got where it is: one row for each status transition a property has made.
 *
 * <p>The current status lives on {@link Property}; this table is the trail behind it, so the
 * register can answer "when was this listed, and by whom" without reconstructing it from logs.</p>
 *
 * <p>Carries {@code changed_at} / {@code changed_by} rather than the housekeeping columns, because
 * a transition is a fact that happened at a moment and is never edited. It therefore extends no
 * base class: there is no soft delete (a transition is not archived, it is history) and there is no
 * updater (nothing updates it).</p>
 */
@Entity
@Table(name = "property_status_history")
public class PropertyStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /** Null for the first transition - a property enters the register without coming from anywhere. */
  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 30)
  private PropertyStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 30)
  private PropertyStatus toStatus;

  @Column(name = "reason", length = 500)
  private String reason;

  /**
   * Who made the change. The {@code users} table has no entity in the current model, so this is
   * kept as a plain identifier rather than a fabricated association to a class that does not exist.
   */
  @Column(name = "changed_by")
  private UUID changedBy;

  @Column(name = "changed_at", nullable = false)
  private LocalDateTime changedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public PropertyStatus getFromStatus() {
    return fromStatus;
  }

  public void setFromStatus(PropertyStatus fromStatus) {
    this.fromStatus = fromStatus;
  }

  public PropertyStatus getToStatus() {
    return toStatus;
  }

  public void setToStatus(PropertyStatus toStatus) {
    this.toStatus = toStatus;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public UUID getChangedBy() {
    return changedBy;
  }

  public void setChangedBy(UUID changedBy) {
    this.changedBy = changedBy;
  }

  public LocalDateTime getChangedAt() {
    return changedAt;
  }

  public void setChangedAt(LocalDateTime changedAt) {
    this.changedAt = changedAt;
  }
}
