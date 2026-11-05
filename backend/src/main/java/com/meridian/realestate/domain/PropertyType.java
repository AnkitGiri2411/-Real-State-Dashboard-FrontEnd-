package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A category a property is classified into - Apartment, Warehouse, Land / Plot.
 *
 * <p>Reference data, not business records: seeded by the schema migration because every
 * installation needs them, and never created through the API.</p>
 *
 * <p>{@code leasable} is a real business flag rather than a label. A plot of land cannot be let
 * in the sense an apartment can, so including it in an occupancy percentage would understate the
 * portfolio's performance. It is excluded at the source, which is why this column exists.</p>
 */
@Entity
@Table(name = "property_types")
public class PropertyType extends ReferenceData {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 40, unique = true)
  private String code;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "leasable", nullable = false)
  private boolean leasable = true;

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

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public boolean isLeasable() {
    return leasable;
  }

  public void setLeasable(boolean leasable) {
    this.leasable = leasable;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }
}
