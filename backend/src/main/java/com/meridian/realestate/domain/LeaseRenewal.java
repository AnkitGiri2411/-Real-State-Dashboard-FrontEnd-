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
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The renewal conversation for a lease, tracked separately from the leases themselves.
 *
 * <p>Tables {@code lease_renewals}. A renewal offer exists before the new lease does, and its
 * outcome is what tells the firm whether a tenancy is continuing.</p>
 *
 * <p>{@link #status} is {@link LeaseRenewalStatus}, the values of {@code ck_lease_renewals_status}.
 * The partial unique index {@code uq_lease_renewals_open} allows one open conversation per lease -
 * the pair {@link LeaseRenewalStatus#isOpen()} names.</p>
 *
 * <p>Once agreed, {@link #renewedLease} names the lease the renewal produced. The schema's
 * {@code ck_lease_renewals_decision} pairs the outcome with its evidence: an {@code ACCEPTED}
 * renewal names the lease and a decision moment, a {@code DECLINED} one records the decision but no
 * lease, and an open one has neither.</p>
 *
 * <p><b>Money.</b> {@code proposed_rent} is {@code numeric(12, 2)}, mapped as {@link BigDecimal} at
 * scale 2. It is a proposal, not a charge - nothing is billed from it; the charge is a
 * {@link RentInvoice} and the rent in force is a {@link LeaseTerm}.</p>
 *
 * <p>Soft delete, hence {@link Auditable}.</p>
 */
@Entity
@Table(name = "lease_renewals")
public class LeaseRenewal extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The lease being renewed. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lease_id", nullable = false)
  private Lease lease;

  /** The lease this renewal produced; set only once the renewal is agreed. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "renewed_lease_id")
  private Lease renewedLease;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private LeaseRenewalStatus status = LeaseRenewalStatus.PENDING;

  @Column(name = "proposed_at")
  private LocalDateTime proposedAt;

  @Column(name = "proposed_rent", precision = 12, scale = 2)
  private BigDecimal proposedRent;

  @Column(name = "proposed_start_date")
  private LocalDate proposedStartDate;

  @Column(name = "proposed_end_date")
  private LocalDate proposedEndDate;

  @Column(name = "decided_at")
  private LocalDateTime decidedAt;

  /** The user who took the decision. Raw identifier - the users slice is not part of this module. */
  @Column(name = "decided_by")
  private UUID decidedBy;

  @Column(name = "decision_notes", length = 1000)
  private String decisionNotes;

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

  public Lease getRenewedLease() {
    return renewedLease;
  }

  public void setRenewedLease(Lease renewedLease) {
    this.renewedLease = renewedLease;
  }

  public LeaseRenewalStatus getStatus() {
    return status;
  }

  public void setStatus(LeaseRenewalStatus status) {
    this.status = status;
  }

  public LocalDateTime getProposedAt() {
    return proposedAt;
  }

  public void setProposedAt(LocalDateTime proposedAt) {
    this.proposedAt = proposedAt;
  }

  public BigDecimal getProposedRent() {
    return proposedRent;
  }

  public void setProposedRent(BigDecimal proposedRent) {
    this.proposedRent = proposedRent;
  }

  public LocalDate getProposedStartDate() {
    return proposedStartDate;
  }

  public void setProposedStartDate(LocalDate proposedStartDate) {
    this.proposedStartDate = proposedStartDate;
  }

  public LocalDate getProposedEndDate() {
    return proposedEndDate;
  }

  public void setProposedEndDate(LocalDate proposedEndDate) {
    this.proposedEndDate = proposedEndDate;
  }

  public LocalDateTime getDecidedAt() {
    return decidedAt;
  }

  public void setDecidedAt(LocalDateTime decidedAt) {
    this.decidedAt = decidedAt;
  }

  public UUID getDecidedBy() {
    return decidedBy;
  }

  public void setDecidedBy(UUID decidedBy) {
    this.decidedBy = decidedBy;
  }

  public String getDecisionNotes() {
    return decisionNotes;
  }

  public void setDecisionNotes(String decisionNotes) {
    this.decisionNotes = decisionNotes;
  }
}
