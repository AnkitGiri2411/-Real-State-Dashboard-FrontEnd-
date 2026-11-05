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
 * One transition in a listing's publication state.
 *
 * <p>Carries {@code changed_at} / {@code changed_by} rather than the audit block, because a status
 * history row is written once and never altered - which is the whole point of it. It therefore does
 * not extend an audit base class.</p>
 */
@Entity
@Table(name = "listing_status_history")
public class ListingStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "listing_id", nullable = false)
  private Listing listing;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 30)
  private ListingStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 30)
  private ListingStatus toStatus;

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

  public Listing getListing() {
    return listing;
  }

  public void setListing(Listing listing) {
    this.listing = listing;
  }

  public ListingStatus getFromStatus() {
    return fromStatus;
  }

  public void setFromStatus(ListingStatus fromStatus) {
    this.fromStatus = fromStatus;
  }

  public ListingStatus getToStatus() {
    return toStatus;
  }

  public void setToStatus(ListingStatus toStatus) {
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
