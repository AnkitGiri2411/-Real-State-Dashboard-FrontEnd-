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
 * A party that holds an interest in the portfolio.
 *
 * <p>The owner is a record rather than a name typed onto a property, because an owner's share of a
 * portfolio's value and income is derived from what they hold, and because the same owner recurs
 * across properties. What they hold, and for how long, lives in {@link PropertyOwnership}.</p>
 *
 * <p>The shape of the record depends on {@link OwnerType}: an entity has no occupation, and an
 * individual has no tax registration in the sense a company does. Both kinds of field are therefore
 * present and mostly null, which is honest - the schema keeps the columns because the firm does
 * eventually learn a company's tax reference, and losing it because the table could not hold it
 * would be worse.</p>
 *
 * <p>{@code email} is stored lower-cased by the database (see {@code ck_owners_email_lower}), which
 * is why the repository's email lookups are case-insensitive and why it matters: matching on a
 * mixed-case address would create a second owner nobody could find predictably.</p>
 */
@Entity
@Table(name = "owners")
public class Owner extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "owner_code", nullable = false, length = 30, unique = true)
  private String ownerCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "owner_type", nullable = false, length = 30)
  private OwnerType ownerType;

  /** The name the desk says out loud: a person's name, or a company's. */
  @Column(name = "display_name", nullable = false, length = 200)
  private String displayName;

  /** The natural person to call, when the owner is an entity rather than a person. */
  @Column(name = "primary_contact_name", length = 200)
  private String primaryContactName;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "phone", length = 30)
  private String phone;

  @Column(name = "alternate_phone", length = 30)
  private String alternatePhone;

  /** An opaque identity reference: this product records that it exists, and never validates it. */
  @Column(name = "id_reference", length = 60)
  private String idReference;

  /** An opaque tax reference, on the same terms as {@link #idReference}. */
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
  private OwnerStatus status = OwnerStatus.ACTIVE;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getOwnerCode() {
    return ownerCode;
  }

  public void setOwnerCode(String ownerCode) {
    this.ownerCode = ownerCode;
  }

  public OwnerType getOwnerType() {
    return ownerType;
  }

  public void setOwnerType(OwnerType ownerType) {
    this.ownerType = ownerType;
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

  public OwnerStatus getStatus() {
    return status;
  }

  public void setStatus(OwnerStatus status) {
    this.status = status;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
