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
 * One transition in a viewing's state.
 *
 * <p>Carries {@code changed_at} / {@code changed_by} rather than the audit block, because a status
 * history row is written once and never altered - which is the whole point of it. It therefore does
 * not extend an audit base class.</p>
 *
 * <p>This table records the transition only; it is not the place the outcome columns live. When the
 * engine writes the {@code COMPLETED} transition it must set the viewing's {@code completed_at} and
 * {@code feedback} in the same statement, because the viewing's own outcome constraint refuses a
 * half-applied outcome. See {@link ViewingStatus}.</p>
 */
@Entity
@Table(name = "viewing_status_history")
public class ViewingStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "viewing_id", nullable = false)
  private Viewing viewing;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 20)
  private ViewingStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 20)
  private ViewingStatus toStatus;

  @Column(name = "reason", length = 500)
  private String reason;

  /** Who made the change. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "changed_by")
  private User changedBy;

  @Column(name = "changed_at", nullable = false)
  private LocalDateTime changedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Viewing getViewing() {
    return viewing;
  }

  public void setViewing(Viewing viewing) {
    this.viewing = viewing;
  }

  public ViewingStatus getFromStatus() {
    return fromStatus;
  }

  public void setFromStatus(ViewingStatus fromStatus) {
    this.fromStatus = fromStatus;
  }

  public ViewingStatus getToStatus() {
    return toStatus;
  }

  public void setToStatus(ViewingStatus toStatus) {
    this.toStatus = toStatus;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public User getChangedBy() {
    return changedBy;
  }

  public void setChangedBy(User changedBy) {
    this.changedBy = changedBy;
  }

  public LocalDateTime getChangedAt() {
    return changedAt;
  }

  public void setChangedAt(LocalDateTime changedAt) {
    this.changedAt = changedAt;
  }
}
