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
 * How a unit got where it is: one row for each status transition it has made.
 *
 * <p>The current status lives on {@link Unit}; this table is the trail behind it, so the unit board
 * can answer "when did this unit become occupied" - the question that turns a snapshot into the
 * letting history behind it.</p>
 *
 * <p>Carries {@code changed_at} / {@code changed_by} rather than the housekeeping columns, because
 * a transition is a fact that happened at a moment and is never edited. It therefore extends no
 * base class: there is no soft delete and no updater.</p>
 */
@Entity
@Table(name = "unit_status_history")
public class UnitStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "unit_id", nullable = false)
  private Unit unit;

  /** Null for the first transition - a unit enters the register without coming from anywhere. */
  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 30)
  private UnitStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 30)
  private UnitStatus toStatus;

  @Column(name = "reason", length = 500)
  private String reason;

  /**
   * Who made the change. The {@code users} table has no entity in the current model, so this is
   * kept as a plain identifier rather than a fabricated association.
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

  public Unit getUnit() {
    return unit;
  }

  public void setUnit(Unit unit) {
    this.unit = unit;
  }

  public UnitStatus getFromStatus() {
    return fromStatus;
  }

  public void setFromStatus(UnitStatus fromStatus) {
    this.fromStatus = fromStatus;
  }

  public UnitStatus getToStatus() {
    return toStatus;
  }

  public void setToStatus(UnitStatus toStatus) {
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
