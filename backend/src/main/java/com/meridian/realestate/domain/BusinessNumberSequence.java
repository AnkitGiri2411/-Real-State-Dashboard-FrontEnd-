package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One human-readable number series: property, unit, lease, invoice, receipt.
 *
 * <p>These series exist because a UUID cannot be read aloud to a tenant, written on a receipt or
 * quoted over the phone. {@code prefix} and {@code padding} decide the shape that is printed
 * ({@code ME-P-000016}) and {@code nextValue} is the counter itself.</p>
 *
 * <p>The counter is the one place in the product where two people working the same portfolio at the
 * same moment can collide, so the service takes a pessimistic row lock
 * ({@code findByCodeForUpdate}) before it increments: the second caller waits for the first to
 * commit and is then handed the following number, rather than the same one. Nothing else about the
 * row is contended, which is why the lock is taken here and not at a coarser grain.</p>
 *
 * <p>There is no base class and no {@code created_at}, because a series has no meaningful creation
 * event in the product's terms - only a current value and when it last moved. {@code updatedAt} is
 * stamped on every change for exactly that reason: it is what a support call needs when a document
 * number has to be explained.</p>
 */
@Entity
@Table(name = "business_number_sequences")
public class BusinessNumberSequence {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 40, unique = true)
  private String code;

  @Column(name = "prefix", nullable = false, length = 20)
  private String prefix;

  @Column(name = "next_value", nullable = false)
  private long nextValue = 1L;

  @Column(name = "padding", nullable = false)
  private int padding = 6;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    if (updatedAt == null) updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getPrefix() {
    return prefix;
  }

  public void setPrefix(String prefix) {
    this.prefix = prefix;
  }

  public long getNextValue() {
    return nextValue;
  }

  public void setNextValue(long nextValue) {
    this.nextValue = nextValue;
  }

  public int getPadding() {
    return padding;
  }

  public void setPadding(int padding) {
    this.padding = padding;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
