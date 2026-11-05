package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A place an enquiry can come from - a portal, a referral, a walk-in.
 *
 * <p>Reference data, not a business record: seeded by the schema migration because every
 * installation needs the same origins, and never created through the API. It carries the two
 * timestamps of {@link ReferenceData} and no audit trail and no soft delete, because nobody
 * archives "Walk-in".</p>
 */
@Entity
@Table(name = "lead_sources")
public class LeadSource extends ReferenceData {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 40, unique = true)
  private String code;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 30)
  private LeadSourceCategory category;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "display_order", nullable = false)
  private int displayOrder = 0;

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

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public LeadSourceCategory getCategory() {
    return category;
  }

  public void setCategory(LeadSourceCategory category) {
    this.category = category;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }
}
