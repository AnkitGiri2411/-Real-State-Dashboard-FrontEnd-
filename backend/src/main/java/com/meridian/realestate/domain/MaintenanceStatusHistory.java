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
 * One recorded transition in a maintenance request's status, oldest first per request.
 *
 * <p><b>No audit base class, and deliberately so.</b> This table is an append-only trail: it carries
 * {@code changed_at} / {@code changed_by}, which are the audit, and it has no {@code created_at},
 * {@code updated_at}, {@code updated_by} or {@code deleted_at}. A history row is a fact about the
 * past and is never edited or soft-deleted, so bending it to fit {@link Auditable} would have meant
 * columns the schema does not have.</p>
 *
 * <p>{@code from_status} is nullable because the first row of a request's history has no prior
 * state. Both statuses are {@link MaintenanceStatus}, the same domain type as
 * {@code maintenance_requests.status}, so a transition cannot name a state the request itself could
 * not be in.</p>
 *
 * <p>The trail exists because the current status alone cannot answer the questions operations
 * actually asks: how long a request sat unassigned, how often it was reopened, whether the vendor
 * was the one who moved it. Those are the differences between consecutive rows here, and they are
 * unrecoverable once only the latest state is kept. The schema indexes the pair
 * ({@code request_id}, {@code changed_at} desc) because that is how the trail is read.</p>
 */
@Entity
@Table(name = "maintenance_status_history")
public class MaintenanceStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "request_id", nullable = false)
  private MaintenanceRequest request;

  /** The state left; null for the first entry in a request's history. */
  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 20)
  private MaintenanceStatus fromStatus;

  /** The state entered. */
  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 20)
  private MaintenanceStatus toStatus;

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

  public MaintenanceRequest getRequest() {
    return request;
  }

  public void setRequest(MaintenanceRequest request) {
    this.request = request;
  }

  public MaintenanceStatus getFromStatus() {
    return fromStatus;
  }

  public void setFromStatus(MaintenanceStatus fromStatus) {
    this.fromStatus = fromStatus;
  }

  public MaintenanceStatus getToStatus() {
    return toStatus;
  }

  public void setToStatus(MaintenanceStatus toStatus) {
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
