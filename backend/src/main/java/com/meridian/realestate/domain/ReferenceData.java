package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.LocalDateTime;

/**
 * The shape of a lookup table: a code, a name and a display order, with timestamps but no audit
 * trail and no soft delete.
 *
 * <p>Property types, unit types, expense categories, lead sources, listing channels and payment
 * methods are categories rather than records. Nobody archives "Apartment", so the columns that
 * track who changed a row and when it was removed would never carry a value - and a column that is
 * always null is worse than no column, because it invites a reader to believe it means something.
 *
 * <p>These tables also carry {@code created_at} / {@code updated_at}, which is why they are not
 * modelled as bare identifier pairs.</p>
 */
@MappedSuperclass
public abstract class ReferenceData {

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
