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
 * An empanelled trade the firm calls out to do work.
 *
 * <p>A vendor is a party the portfolio does business with, not a staff account: the firm does not
 * manage the plumber, it engages them. The contact block is kept as components rather than one
 * address blob so a work order can be routed to the right person at the right number, and the
 * address mirror that of {@link Property} so the two can be grouped by city without a parser.</p>
 *
 * <p>{@code is_empanelled} is the approved-list flag. It is a preference rather than a gate - a
 * non-empanelled vendor may still be used in an emergency - which is why it does not appear in any
 * constraint forbidding assignment. {@code status}, by contrast, is the real gate: a
 * {@link VendorStatus#SUSPENDED} vendor must not be given new work even though its history stays
 * readable. See {@link VendorStatus#isAssignable()}.</p>
 *
 * <p>{@code rating} is nullable on purpose. An unrated vendor is a genuinely different state from a
 * poorly rated one, and defaulting the unrated to zero would drag every average down with vendors
 * nobody has judged yet. The schema allows 1-5 and the entity keeps it nullable so the difference
 * survives.</p>
 *
 * <p>The email is stored lower-cased - {@code ck_vendors_email_lower} enforces it - because the
 * caller matches on the address and a mixed-case row would be a vendor no search can find.</p>
 */
@Entity
@Table(name = "vendors")
public class Vendor extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "vendor_code", nullable = false, length = 30, unique = true)
  private String vendorCode;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 30)
  private VendorCategory category;

  @Column(name = "contact_name", length = 200)
  private String contactName;

  @Column(name = "phone", length = 30)
  private String phone;

  @Column(name = "alternate_phone", length = 30)
  private String alternatePhone;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "tax_reference", length = 60)
  private String taxReference;

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

  /** True where the vendor is on the firm's approved list; a preference, not a gate. */
  @Column(name = "is_empanelled", nullable = false)
  private boolean empanelled = true;

  /** 1-5 as the schema constrains it. Null means unrated, not badly rated. */
  @Column(name = "rating")
  private Integer rating;

  @Column(name = "payment_terms_days")
  private Integer paymentTermsDays;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private VendorStatus status = VendorStatus.ACTIVE;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  /** True when the vendor may be given new work. */
  public boolean isAssignable() {
    return status != null && status.isAssignable();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getVendorCode() {
    return vendorCode;
  }

  public void setVendorCode(String vendorCode) {
    this.vendorCode = vendorCode;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public VendorCategory getCategory() {
    return category;
  }

  public void setCategory(VendorCategory category) {
    this.category = category;
  }

  public String getContactName() {
    return contactName;
  }

  public void setContactName(String contactName) {
    this.contactName = contactName;
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

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getTaxReference() {
    return taxReference;
  }

  public void setTaxReference(String taxReference) {
    this.taxReference = taxReference;
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

  public boolean isEmpanelled() {
    return empanelled;
  }

  public void setEmpanelled(boolean empanelled) {
    this.empanelled = empanelled;
  }

  public Integer getRating() {
    return rating;
  }

  public void setRating(Integer rating) {
    this.rating = rating;
  }

  public Integer getPaymentTermsDays() {
    return paymentTermsDays;
  }

  public void setPaymentTermsDays(Integer paymentTermsDays) {
    this.paymentTermsDays = paymentTermsDays;
  }

  public VendorStatus getStatus() {
    return status;
  }

  public void setStatus(VendorStatus status) {
    this.status = status;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
