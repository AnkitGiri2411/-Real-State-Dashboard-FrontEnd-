package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * A block within a property.
 *
 * <p>Several properties in the portfolio are multi-block, so the unit board groups by building and
 * then by floor. A single-block property has exactly one building, which keeps every unit's
 * location path uniform - a unit always sits at property / building / floor rather than sometimes
 * at property / floor.</p>
 *
 * <p>{@code building_code} is unique within its property rather than globally, because "Block A"
 * exists in more than one property. That is a real composite key, not a real uniqueness the
 * database does not hold, so the constraint is declared as the pair.</p>
 *
 * <p>Carries the full audit trail - a block is a record people create, rename and archive.</p>
 */
@Entity
@Table(
    name = "buildings",
    uniqueConstraints = @UniqueConstraint(columnNames = {"property_id", "building_code"}))
public class Building extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  @Column(name = "building_code", nullable = false, length = 30)
  private String buildingCode;

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  /** Number of storeys, which bounds the floors a unit may sit on. Nullable when not recorded. */
  @Column(name = "floors")
  private Integer floors;

  @Column(name = "year_built")
  private Integer yearBuilt;

  @Column(name = "description", length = 500)
  private String description;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public String getBuildingCode() {
    return buildingCode;
  }

  public void setBuildingCode(String buildingCode) {
    this.buildingCode = buildingCode;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Integer getFloors() {
    return floors;
  }

  public void setFloors(Integer floors) {
    this.floors = floors;
  }

  public Integer getYearBuilt() {
    return yearBuilt;
  }

  public void setYearBuilt(Integer yearBuilt) {
    this.yearBuilt = yearBuilt;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
