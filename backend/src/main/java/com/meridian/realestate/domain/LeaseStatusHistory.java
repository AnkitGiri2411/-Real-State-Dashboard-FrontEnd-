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
 * One recorded transition in a lease's status, oldest first per lease.
 *
 * <p>Tables {@code lease_status_history}.</p>
 *
 * <p><b>No audit base class, and deliberately so.</b> This table is an append-only trail: it carries
 * {@code changed_at} / {@code changed_by}, which are the audit, and it has no {@code created_at},
 * {@code updated_at}, {@code updated_by} or {@code deleted_at}. A history row is a fact about the
 * past and is never edited or soft-deleted, so bending it to fit {@link Auditable} would have meant
 * columns the schema does not have. It is the reason this one entity extends nothing.</p>
 *
 * <p>{@code from_status} is nullable because the first row of a lease's history has no prior state;
 * {@code to_status} is the state entered and is never null. Both are {@link LeaseStatus}, the same
 * domain type as {@code leases.status}, so a transition cannot name a state the lease could not be
 * in.</p>
 */
@Entity
@Table(name = "lease_status_history")
public class LeaseStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lease_id", nullable = false)
  private Lease lease;

  /** The state left; null for the first entry in a lease's history. */
  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 20)
  private LeaseStatus fromStatus;

  /** The state entered. */
  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 20)
  private LeaseStatus toStatus;

  @Column(name = "reason", length = 500)
  private String reason;

  /** The user who made the change. Raw identifier - the users slice is not part of this module. */
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

  public Lease getLease() {
    return lease;
  }

  public void setLease(Lease lease) {
    this.lease = lease;
  }

  public LeaseStatus getFromStatus() {
    return fromStatus;
  }

  public void setFromStatus(LeaseStatus fromStatus) {
    this.fromStatus = fromStatus;
  }

  public LeaseStatus getToStatus() {
    return toStatus;
  }

  public void setToStatus(LeaseStatus toStatus) {
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
