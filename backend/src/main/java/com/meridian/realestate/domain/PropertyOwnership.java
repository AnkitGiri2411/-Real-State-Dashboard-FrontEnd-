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
 * One owner's holding in one property, for a period, with a share.
 *
 * <p>Ownership is a relationship rather than a column on the property because a portfolio is
 * routinely held by several parties at once - a family, a company and a development partner on one
 * title - and because a holding has a start and an end. It is this row, not a hand-maintained
 * number, that an owner report derives a share of value and income from.</p>
 *
 * <p><strong>Business rule the schema cannot enforce (the service's job):</strong> the schema
 * constrains a single row to {@code (0, 100]} and the database additionally requires the
 * percentage to be positive and no more than the whole. The rule that the holdings in force for one
 * property must sum to <em>exactly 100%</em> spans rows and therefore cannot be a check constraint.
 * It must be enforced in the service layer, inside a single transaction that reads every holding on
 * the property, applies the proposed change and refuses to leave the total other than 100 - which
 * is why the repository exposes the sum and the open holdings in the same shape the check needs.
 * Closing a holding or dating a new one is the same transaction as the check, because a
 * momentarily-too-small or too-large total is still a wrong answer if it is ever committed.</p>
 *
 * <p>A holding that has ended is dated ({@code effectiveTo}) rather than deleted, so a past
 * ownership stays reportable instead of becoming invisible.</p>
 */
@Entity
@Table(name = "property_ownerships")
public class PropertyOwnership extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_id", nullable = false)
  private Owner owner;

  /** Constrained by the database to {@code > 0 AND <= 100}; the per-property total is not. */
  @Column(name = "ownership_percentage", nullable = false, precision = 5, scale = 2)
  private BigDecimal ownershipPercentage;

  @Enumerated(EnumType.STRING)
  @Column(name = "relationship", nullable = false, length = 30)
  private OwnershipRelationship relationship = OwnershipRelationship.CO_OWNER;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  /** Null means the holding is still in force. A closed holding is dated, never deleted. */
  @Column(name = "effective_to")
  private LocalDate effectiveTo;

  @Column(name = "notes", length = 500)
  private String notes;

  /** True while this holding is still in force, which is what an owner report reads. */
  public boolean isOpen() {
    return effectiveTo == null;
  }

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

  public Owner getOwner() {
    return owner;
  }

  public void setOwner(Owner owner) {
    this.owner = owner;
  }

  public BigDecimal getOwnershipPercentage() {
    return ownershipPercentage;
  }

  public void setOwnershipPercentage(BigDecimal ownershipPercentage) {
    this.ownershipPercentage = ownershipPercentage;
  }

  public OwnershipRelationship getRelationship() {
    return relationship;
  }

  public void setRelationship(OwnershipRelationship relationship) {
    this.relationship = relationship;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
