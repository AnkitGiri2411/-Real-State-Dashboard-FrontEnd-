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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The smallest lettable or sellable thing in the portfolio.
 *
 * <p>A property is the asset; a unit is what a tenant actually occupies and what a buyer actually
 * buys. Every occupancy, vacancy, rent-roll and unit-board figure is computed over units, which is
 * why this is the busiest read in the product.</p>
 *
 * <p>{@code leasable} lives here as well as on the property type. A building's common areas, a
 * caretaker's flat or a marketing suite are real units but they are not available to let, and
 * counting them would understate occupancy - so they exist in the register and are excluded from
 * every occupancy figure at the source rather than by a filter someone has to remember to apply.
 * The database enforces the matching rule: a non-lettable unit cannot sit in a lettable status.</p>
 *
 * <p>{@code floorNumber} is a signed number so basements are real floors rather than a string
 * someone has to parse; {@code floorLabel} carries what is printed on the plan ("G", "B1", "12"),
 * because a floor reads as a name at the desk and as a number to a sort. The unit board groups by
 * building and then orders by this pair.</p>
 */
@Entity
@Table(
    name = "units",
    uniqueConstraints = @UniqueConstraint(columnNames = {"property_id", "unit_number"}))
public class Unit extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "unit_code", nullable = false, length = 30, unique = true)
  private String unitCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /**
   * The block the unit sits in. Nullable, because a standalone property has no block to name even
   * though every multi-block property does.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "building_id")
  private Building building;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "unit_type_id", nullable = false)
  private UnitType unitType;

  @Column(name = "unit_number", nullable = false, length = 30)
  private String unitNumber;

  @Column(name = "floor_number", nullable = false)
  private int floorNumber = 0;

  @Column(name = "floor_label", length = 30)
  private String floorLabel;

  @Column(name = "bedrooms", nullable = false)
  private int bedrooms = 0;

  @Column(name = "bathrooms", nullable = false)
  private int bathrooms = 0;

  @Column(name = "balconies", nullable = false)
  private int balconies = 0;

  @Column(name = "carpet_area_sqft", precision = 12, scale = 2)
  private BigDecimal carpetAreaSqft;

  @Column(name = "built_up_area_sqft", precision = 12, scale = 2)
  private BigDecimal builtUpAreaSqft;

  @Column(name = "parking_slots", nullable = false)
  private int parkingSlots = 0;

  @Enumerated(EnumType.STRING)
  @Column(name = "furnishing", nullable = false, length = 30)
  private UnitFurnishing furnishing = UnitFurnishing.UNFURNISHED;

  @Enumerated(EnumType.STRING)
  @Column(name = "facing", length = 10)
  private UnitFacing facing;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private UnitStatus status = UnitStatus.AVAILABLE;

  /** False for common areas and other real stock that cannot be let; see the class note. */
  @Column(name = "leasable", nullable = false)
  private boolean leasable = true;

  /** Monthly rent asked for a letting. Null for stock that is not offered to let. */
  @Column(name = "asking_rent", precision = 12, scale = 2)
  private BigDecimal askingRent;

  /** Sale price asked for an owned unit. Null for stock that is not offered for sale. */
  @Column(name = "asking_price", precision = 14, scale = 2)
  private BigDecimal askingPrice;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getUnitCode() {
    return unitCode;
  }

  public void setUnitCode(String unitCode) {
    this.unitCode = unitCode;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Building getBuilding() {
    return building;
  }

  public void setBuilding(Building building) {
    this.building = building;
  }

  public UnitType getUnitType() {
    return unitType;
  }

  public void setUnitType(UnitType unitType) {
    this.unitType = unitType;
  }

  public String getUnitNumber() {
    return unitNumber;
  }

  public void setUnitNumber(String unitNumber) {
    this.unitNumber = unitNumber;
  }

  public int getFloorNumber() {
    return floorNumber;
  }

  public void setFloorNumber(int floorNumber) {
    this.floorNumber = floorNumber;
  }

  public String getFloorLabel() {
    return floorLabel;
  }

  public void setFloorLabel(String floorLabel) {
    this.floorLabel = floorLabel;
  }

  public int getBedrooms() {
    return bedrooms;
  }

  public void setBedrooms(int bedrooms) {
    this.bedrooms = bedrooms;
  }

  public int getBathrooms() {
    return bathrooms;
  }

  public void setBathrooms(int bathrooms) {
    this.bathrooms = bathrooms;
  }

  public int getBalconies() {
    return balconies;
  }

  public void setBalconies(int balconies) {
    this.balconies = balconies;
  }

  public BigDecimal getCarpetAreaSqft() {
    return carpetAreaSqft;
  }

  public void setCarpetAreaSqft(BigDecimal carpetAreaSqft) {
    this.carpetAreaSqft = carpetAreaSqft;
  }

  public BigDecimal getBuiltUpAreaSqft() {
    return builtUpAreaSqft;
  }

  public void setBuiltUpAreaSqft(BigDecimal builtUpAreaSqft) {
    this.builtUpAreaSqft = builtUpAreaSqft;
  }

  public int getParkingSlots() {
    return parkingSlots;
  }

  public void setParkingSlots(int parkingSlots) {
    this.parkingSlots = parkingSlots;
  }

  public UnitFurnishing getFurnishing() {
    return furnishing;
  }

  public void setFurnishing(UnitFurnishing furnishing) {
    this.furnishing = furnishing;
  }

  public UnitFacing getFacing() {
    return facing;
  }

  public void setFacing(UnitFacing facing) {
    this.facing = facing;
  }

  public UnitStatus getStatus() {
    return status;
  }

  public void setStatus(UnitStatus status) {
    this.status = status;
  }

  public boolean isLeasable() {
    return leasable;
  }

  public void setLeasable(boolean leasable) {
    this.leasable = leasable;
  }

  public BigDecimal getAskingRent() {
    return askingRent;
  }

  public void setAskingRent(BigDecimal askingRent) {
    this.askingRent = askingRent;
  }

  public BigDecimal getAskingPrice() {
    return askingPrice;
  }

  public void setAskingPrice(BigDecimal askingPrice) {
    this.askingPrice = askingPrice;
  }
}
