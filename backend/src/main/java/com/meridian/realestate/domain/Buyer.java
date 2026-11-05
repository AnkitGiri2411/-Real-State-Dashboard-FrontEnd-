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
 * A party that acquires a unit under a sale.
 *
 * <p>The other side of demand from {@link Tenant}: a buyer is a client with a relationship the firm
 * keeps, so the record has its own code and identity rather than being a name typed onto an offer.
 * What they are looking for is not stored here but in {@link BuyerRequirement} - a buyer's brief
 * changes over time and its history explains why a property was or was not shown to them.</p>
 *
 * <p>{@link BuyerStatus#CONVERTED} is a lifecycle fact rather than an archive state, so a buyer who
 * has completed a purchase stays readable and is excluded from new-buyer reporting instead of being
 * deleted.</p>
 */
@Entity
@Table(name = "buyers")
public class Buyer extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "buyer_code", nullable = false, length = 30, unique = true)
  private String buyerCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "buyer_type", nullable = false, length = 30)
  private BuyerType buyerType;

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
  private BuyerStatus status = BuyerStatus.ACTIVE;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getBuyerCode() {
    return buyerCode;
  }

  public void setBuyerCode(String buyerCode) {
    this.buyerCode = buyerCode;
  }

  public BuyerType getBuyerType() {
    return buyerType;
  }

  public void setBuyerType(BuyerType buyerType) {
    this.buyerType = buyerType;
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

  public BuyerStatus getStatus() {
    return status;
  }

  public void setStatus(BuyerStatus status) {
    this.status = status;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
