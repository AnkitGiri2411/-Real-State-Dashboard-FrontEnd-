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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A step in a lease's rent schedule: what the rent is, from when, and the escalation that produced
 * it.
 *
 * <p>Tables {@code lease_terms}. Separate from the lease because the rent changes during a tenancy
 * while the contract does not, and because a rent roll for a past month must be answerable at the
 * rate that actually applied then.</p>
 *
 * <p><b>"The rent in force on date X" is single-valued.</b> Two partial unique indexes make that a
 * fact rather than a hope:</p>
 * <ul>
 *   <li>{@code uq_lease_terms_open} - at most one open-ended term
 *       ({@code effective_to IS NULL}) per lease, so there is always a single current rent;</li>
 *   <li>{@code uq_lease_terms_effective} - at most one term per
 *       {@code (lease_id, effective_from)}, so two revisions cannot start on the same day.</li>
 * </ul>
 *
 * <p><b>Money.</b> {@code rent_amount} is {@code numeric(12, 2)}, mapped as {@link BigDecimal} at
 * scale 2. {@code rent_amount > 0} is enforced by {@code ck_lease_terms_rent_positive}: a term is
 * the rent, never a credit. (Credits live on invoice lines, where they are legitimate.)</p>
 *
 * <p>Soft delete, because a superseded term still explains a past rent roll - hence
 * {@link Auditable}.</p>
 */
@Entity
@Table(name = "lease_terms")
public class LeaseTerm extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lease_id", nullable = false)
  private Lease lease;

  @Column(name = "term_number", nullable = false)
  private int termNumber;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  /** Null on the term currently in force - the one {@code uq_lease_terms_open} allows. */
  @Column(name = "effective_to")
  private LocalDate effectiveTo;

  @Column(name = "rent_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal rentAmount;

  @Column(name = "escalation_percent", precision = 5, scale = 2)
  private BigDecimal escalationPercent;

  @Column(name = "notes", length = 500)
  private String notes;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Lease getLease() {
    return lease;
  }

  public void setLease(Lease lease) {
    this.lease = lease;
  }

  public int getTermNumber() {
    return termNumber;
  }

  public void setTermNumber(int termNumber) {
    this.termNumber = termNumber;
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

  public BigDecimal getRentAmount() {
    return rentAmount;
  }

  public void setRentAmount(BigDecimal rentAmount) {
    this.rentAmount = rentAmount;
  }

  public BigDecimal getEscalationPercent() {
    return escalationPercent;
  }

  public void setEscalationPercent(BigDecimal escalationPercent) {
    this.escalationPercent = escalationPercent;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
