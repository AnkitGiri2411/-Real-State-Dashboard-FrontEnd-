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
import java.time.LocalDate;
import java.util.UUID;

/**
 * The asset register: one property the firm manages.
 *
 * <p>The address is kept as components rather than one blob so the portfolio can be grouped and
 * filtered by city and locality, which is how the register is actually read.</p>
 *
 * <p>Position is two nullable.columns, because the portfolio map is a real projection over real
 * coordinates rather than a decorative graphic. A property whose position is not known is omitted
 * from the map rather than plotted at a guessed point, and the database enforces that the pair is
 * all-or-nothing.</p>
 */
@Entity
@Table(name = "properties")
public class Property extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "property_code", nullable = false, length = 30, unique = true)
  private String propertyCode;

  @Column(name = "name", nullable = false, length = 180)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_type_id", nullable = false)
  private PropertyType propertyType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private PropertyStatus status = PropertyStatus.DRAFT;

  @Column(name = "address_line1", nullable = false, length = 200)
  private String addressLine1;

  @Column(name = "address_line2", length = 200)
  private String addressLine2;

  @Column(name = "locality", length = 120)
  private String locality;

  @Column(name = "city", nullable = false, length = 120)
  private String city;

  @Column(name = "state", length = 120)
  private String state;

  @Column(name = "postal_code", length = 20)
  private String postalCode;

  @Column(name = "country", nullable = false, length = 80)
  private String country = "India";

  @Column(name = "latitude", precision = 9, scale = 6)
  private BigDecimal latitude;

  @Column(name = "longitude", precision = 9, scale = 6)
  private BigDecimal longitude;

  @Column(name = "description", columnDefinition = "text")
  private String description;

  @Column(name = "year_built")
  private Integer yearBuilt;

  @Column(name = "land_area_sqft", precision = 14, scale = 2)
  private BigDecimal landAreaSqft;

  @Column(name = "built_area_sqft", precision = 14, scale = 2)
  private BigDecimal builtAreaSqft;

  @Column(name = "acquisition_value", precision = 14, scale = 2)
  private BigDecimal acquisitionValue;

  @Column(name = "current_valuation", precision = 14, scale = 2)
  private BigDecimal currentValuation;

  @Column(name = "valuation_date")
  private LocalDate valuationDate;

  /** True when this property can be plotted, which is the condition for appearing on the map. */
  public boolean hasPosition() {
    return latitude != null && longitude != null;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getPropertyCode() {
    return propertyCode;
  }

  public void setPropertyCode(String propertyCode) {
    this.propertyCode = propertyCode;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public PropertyType getPropertyType() {
    return propertyType;
  }

  public void setPropertyType(PropertyType propertyType) {
    this.propertyType = propertyType;
  }

  public PropertyStatus getStatus() {
    return status;
  }

  public void setStatus(PropertyStatus status) {
    this.status = status;
  }

  public String getAddressLine1() {
    return addressLine1;
  }

  public void setAddressLine1(String addressLine1) {
    this.addressLine1 = addressLine1;
  }

  public String getAddressLine2() {
    return addressLine2;
  }

  public void setAddressLine2(String addressLine2) {
    this.addressLine2 = addressLine2;
  }

  public String getLocality() {
    return locality;
  }

  public void setLocality(String locality) {
    this.locality = locality;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getPostalCode() {
    return postalCode;
  }

  public void setPostalCode(String postalCode) {
    this.postalCode = postalCode;
  }

  public String getCountry() {
    return country;
  }

  public void setCountry(String country) {
    this.country = country;
  }

  public BigDecimal getLatitude() {
    return latitude;
  }

  public void setLatitude(BigDecimal latitude) {
    this.latitude = latitude;
  }

  public BigDecimal getLongitude() {
    return longitude;
  }

  public void setLongitude(BigDecimal longitude) {
    this.longitude = longitude;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Integer getYearBuilt() {
    return yearBuilt;
  }

  public void setYearBuilt(Integer yearBuilt) {
    this.yearBuilt = yearBuilt;
  }

  public BigDecimal getLandAreaSqft() {
    return landAreaSqft;
  }

  public void setLandAreaSqft(BigDecimal landAreaSqft) {
    this.landAreaSqft = landAreaSqft;
  }

  public BigDecimal getBuiltAreaSqft() {
    return builtAreaSqft;
  }

  public void setBuiltAreaSqft(BigDecimal builtAreaSqft) {
    this.builtAreaSqft = builtAreaSqft;
  }

  public BigDecimal getAcquisitionValue() {
    return acquisitionValue;
  }

  public void setAcquisitionValue(BigDecimal acquisitionValue) {
    this.acquisitionValue = acquisitionValue;
  }

  public BigDecimal getCurrentValuation() {
    return currentValuation;
  }

  public void setCurrentValuation(BigDecimal currentValuation) {
    this.currentValuation = currentValuation;
  }

  public LocalDate getValuationDate() {
    return valuationDate;
  }

  public void setValuationDate(LocalDate valuationDate) {
    this.valuationDate = valuationDate;
  }
}
