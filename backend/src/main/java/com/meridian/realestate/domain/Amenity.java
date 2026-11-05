package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A facility a property can offer - power backup, a lift, a clubhouse.
 *
 * <p>Reference data: seeded by the schema migration because every installation needs the same
 * list, and never created through the API. This table has no timestamps and no soft delete, so it
 * does not extend a base class - a column that would always be null would only invite a reader to
 * believe it meant something.</p>
 *
 * <p>{@code category} groups the amenities on the property page (utility, access, security,
 * parking, logistics, lifestyle). It is a plain string rather than an enum because the schema does
 * not constrain it with a check - a category is a label the interface groups by, not a state.</p>
 */
@Entity
@Table(name = "amenities")
public class Amenity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 50, unique = true)
  private String code;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "category", nullable = false, length = 60)
  private String category;

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

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }
}
