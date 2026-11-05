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
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A buyer's brief: what they want, where, and for how much.
 *
 * <p>Several briefs per buyer are allowed, each with its own {@code isActive} flag, because a
 * buyer's requirement changes over time and the history of what they were looking for is what
 * explains why a property was or was not shown to them. A superseded brief is deactivated rather
 * than deleted, so it stays readable as history.</p>
 *
 * <p>Every bound is nullable on purpose: "anywhere in Pune, two bedrooms or more" is a complete
 * brief, and a NOT NULL budget would force a number the buyer never gave. Null therefore means "no
 * constraint stated", never zero.</p>
 */
@Entity
@Table(name = "buyer_requirements")
public class BuyerRequirement extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "buyer_id", nullable = false)
  private Buyer buyer;

  /** Nullable: a brief may name no property type, which matches anything. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "property_type_id")
  private PropertyType propertyType;

  @Column(name = "preferred_city", length = 120)
  private String preferredCity;

  @Column(name = "preferred_locality", length = 120)
  private String preferredLocality;

  @Column(name = "min_budget", precision = 14, scale = 2)
  private BigDecimal minBudget;

  @Column(name = "max_budget", precision = 14, scale = 2)
  private BigDecimal maxBudget;

  @Column(name = "min_bedrooms")
  private Integer minBedrooms;

  @Column(name = "max_bedrooms")
  private Integer maxBedrooms;

  @Column(name = "min_area_sqft", precision = 12, scale = 2)
  private BigDecimal minAreaSqft;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", nullable = false, length = 30)
  private BuyerRequirementPurpose purpose = BuyerRequirementPurpose.OWN_OCCUPANCY;

  /** False rather than deleted, so a superseded brief stays readable as history. */
  @Column(name = "is_active", nullable = false)
  private boolean isActive = true;

  @Column(name = "notes", length = 1000)
  private String notes;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Buyer getBuyer() {
    return buyer;
  }

  public void setBuyer(Buyer buyer) {
    this.buyer = buyer;
  }

  public PropertyType getPropertyType() {
    return propertyType;
  }

  public void setPropertyType(PropertyType propertyType) {
    this.propertyType = propertyType;
  }

  public String getPreferredCity() {
    return preferredCity;
  }

  public void setPreferredCity(String preferredCity) {
    this.preferredCity = preferredCity;
  }

  public String getPreferredLocality() {
    return preferredLocality;
  }

  public void setPreferredLocality(String preferredLocality) {
    this.preferredLocality = preferredLocality;
  }

  public BigDecimal getMinBudget() {
    return minBudget;
  }

  public void setMinBudget(BigDecimal minBudget) {
    this.minBudget = minBudget;
  }

  public BigDecimal getMaxBudget() {
    return maxBudget;
  }

  public void setMaxBudget(BigDecimal maxBudget) {
    this.maxBudget = maxBudget;
  }

  public Integer getMinBedrooms() {
    return minBedrooms;
  }

  public void setMinBedrooms(Integer minBedrooms) {
    this.minBedrooms = minBedrooms;
  }

  public Integer getMaxBedrooms() {
    return maxBedrooms;
  }

  public void setMaxBedrooms(Integer maxBedrooms) {
    this.maxBedrooms = maxBedrooms;
  }

  public BigDecimal getMinAreaSqft() {
    return minAreaSqft;
  }

  public void setMinAreaSqft(BigDecimal minAreaSqft) {
    this.minAreaSqft = minAreaSqft;
  }

  public BuyerRequirementPurpose getPurpose() {
    return purpose;
  }

  public void setPurpose(BuyerRequirementPurpose purpose) {
    this.purpose = purpose;
  }

  public boolean isActive() {
    return isActive;
  }

  public void setActive(boolean active) {
    isActive = active;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
