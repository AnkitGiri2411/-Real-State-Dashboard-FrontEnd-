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
 * A branch: where an agent sits, and what office a property is reported under.
 *
 * <p>An office has its own address because it is a real place - viewings are booked from it and
 * correspondence is addressed to it - rather than a label on a staff record.</p>
 *
 * <p>{@code isHeadOffice} is a flag rather than a name match, so office-level reporting can
 * attribute to the registered office without depending on how it happens to be spelled. The schema
 * allows exactly one head office among the live rows, enforced by the partial unique index
 * {@code uq_offices_single_head}: two rows claiming to be the registered office would silently
 * double-count.</p>
 */
@Entity
@Table(name = "offices")
public class Office extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "office_code", nullable = false, length = 30, unique = true)
  private String officeCode;

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  @Column(name = "address_line1", length = 200)
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

  @Column(name = "phone", length = 30)
  private String phone;

  @Column(name = "email", length = 255)
  private String email;

  /** True for the registered office; at most one live row may carry it. */
  @Column(name = "is_head_office", nullable = false)
  private boolean isHeadOffice = false;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OfficeStatus status = OfficeStatus.ACTIVE;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getOfficeCode() {
    return officeCode;
  }

  public void setOfficeCode(String officeCode) {
    this.officeCode = officeCode;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
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

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public boolean isHeadOffice() {
    return isHeadOffice;
  }

  public void setHeadOffice(boolean headOffice) {
    isHeadOffice = headOffice;
  }

  public OfficeStatus getStatus() {
    return status;
  }

  public void setStatus(OfficeStatus status) {
    this.status = status;
  }
}
