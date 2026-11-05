package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A party that occupies a unit under a lease.
 *
 * <p>Kept apart from {@link Buyer} rather than folded into one {@code parties} table with a type
 * column, because what the firm knows about the two genuinely differs: a tenant carries an
 * occupation, an employer and an income, a buyer carries a brief. A single table would be half-null
 * on both sides and the brief could not be expressed at all.</p>
 *
 * <p>{@code annualIncome} being null is a real state, distinct from zero: it means the tenant has
 * not disclosed it, and screening must treat an undisclosed income differently from an income of
 * nothing rather than substituting a default.</p>
 *
 * <p>{@link TenantStatus#BLACKLISTED} is checked before an application is accepted, not after - it
 * is a decision the firm has already taken, so no transaction should be able to proceed past it.</p>
 */
@Entity
@Table(name = "tenants")
public class Tenant extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_code", nullable = false, length = 30, unique = true)
  private String tenantCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "tenant_type", nullable = false, length = 30)
  private TenantType tenantType;

  /** The name the desk says out loud: a person's name, or a company's. */
  @Column(name = "display_name", nullable = false, length = 200)
  private String displayName;

  @Column(name = "primary_contact_name", length = 200)
  private String primaryContactName;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "phone", length = 30)
  private String phone;

  @Column(name = "alternate_phone", length = 30)
  private String alternatePhone;

  @Column(name = "id_reference", length = 60)
  private String idReference;

  @Column(name = "tax_reference", length = 60)
  private String taxReference;

  /** Company tenants only; null for an individual. */
  @Column(name = "registration_number", length = 60)
  private String registrationNumber;

  @Column(name = "occupation", length = 120)
  private String occupation;

  @Column(name = "employer", length = 200)
  private String employer;

  /** Null means "not disclosed", which is a different state from an income of zero. */
  @Column(name = "annual_income", precision = 14, scale = 2)
  private BigDecimal annualIncome;

  @Column(name = "address_line1", length = 200)
  private String addressLine1;

  @Column(name = "address_line2", length = 200)
  private String addressLine2;

  @Column(name = "locality", length = 120)
  private String locality;

  @Column(name = "city", length = 120)
  private String city;

  @Column(name = "state", length = 120)
  private String state;

  @Column(name = "postal_code", length = 20)
  private String postalCode;

  @Column(name = "country", nullable = false, length = 80)
  private String country = "India";

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private TenantStatus status = TenantStatus.ACTIVE;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getTenantCode() {
    return tenantCode;
  }

  public void setTenantCode(String tenantCode) {
    this.tenantCode = tenantCode;
  }

  public TenantType getTenantType() {
    return tenantType;
  }

  public void setTenantType(TenantType tenantType) {
    this.tenantType = tenantType;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getPrimaryContactName() {
    return primaryContactName;
  }

  public void setPrimaryContactName(String primaryContactName) {
    this.primaryContactName = primaryContactName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getAlternatePhone() {
    return alternatePhone;
  }

  public void setAlternatePhone(String alternatePhone) {
    this.alternatePhone = alternatePhone;
  }

  public String getIdReference() {
    return idReference;
  }

  public void setIdReference(String idReference) {
    this.idReference = idReference;
  }

  public String getTaxReference() {
    return taxReference;
  }

  public void setTaxReference(String taxReference) {
    this.taxReference = taxReference;
  }

  public String getRegistrationNumber() {
    return registrationNumber;
  }

  public void setRegistrationNumber(String registrationNumber) {
    this.registrationNumber = registrationNumber;
  }

  public String getOccupation() {
    return occupation;
  }

  public void setOccupation(String occupation) {
    this.occupation = occupation;
  }

  public String getEmployer() {
    return employer;
  }

  public void setEmployer(String employer) {
    this.employer = employer;
  }

  public BigDecimal getAnnualIncome() {
    return annualIncome;
  }

  public void setAnnualIncome(BigDecimal annualIncome) {
    this.annualIncome = annualIncome;
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

  public TenantStatus getStatus() {
    return status;
  }

  public void setStatus(TenantStatus status) {
    this.status = status;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
