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
 * The contract that makes a unit occupied - the most load-bearing record on the rent side of the
 * product. Occupancy, the rent roll, collections, lease-expiry reporting and yield are all derived
 * from it.
 *
 * <p>Tables {@code leases}. Soft delete, because the invoices, audit trail and reports that
 * reference a lease must stay coherent after it is archived - hence {@link Auditable}.</p>
 *
 * <p><b>The rent here is the headline, not the authority.</b> {@code rent_amount} and
 * {@code rent_frequency} are how the rent was set when the lease was agreed; the figure that
 * actually applies to a given month is the {@link LeaseTerm} in force on that date, because rent
 * changes during a tenancy while the contract does not, and a rent roll for a past month has to be
 * answerable at the rate that applied then, not today's.</p>
 *
 * <p><b>Money.</b> {@code rent_amount}, {@code deposit_amount} and {@code deposit_refunded_amount}
 * are {@code numeric(12, 2)} and are mapped as {@link BigDecimal} at scale 2. Nothing on this table
 * is ever a {@code double} or a {@code float}: decimal arithmetic happens in the service layer in
 * {@link BigDecimal}, HALF_UP.</p>
 *
 * <p><b>The deposit is a liability, not income.</b> It is tracked here and refunded on exit; it
 * lives nowhere near the rent ledger, so no query can accidentally add a deposit to rental income.</p>
 *
 * <p><b>A renewal is a new lease, not an edit of this one.</b> The old lease keeps its end date and
 * moves to {@link LeaseStatus#RENEWED}; the new lease points back at it through
 * {@link #renewalOfLease}, which is what makes a tenancy's full history reconstructable years
 * later.</p>
 *
 * <p><b>One running lease per unit.</b> The partial unique index {@code uq_leases_one_running_per_unit}
 * enforces it in the database: a unit cannot be let to two tenants at once. {@link #status} is the
 * column that index reads, alongside {@link LeaseStatus#isRunning()}.</p>
 *
 * <p><b>References to other slices.</b> {@code property_id} is modelled as an association because
 * the asset register ({@link Property}) already exists in this module. {@code unit_id},
 * {@code tenant_id} and {@code primary_owner_id} point at tables whose entities are introduced by
 * other slices, so they are held as raw identifiers here; the column - not a half-built object graph
 * - is what this entity is answerable for.</p>
 */
@Entity
@Table(name = "leases")
public class Lease extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "lease_code", nullable = false, length = 30, unique = true)
  private String leaseCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /** The unit let. Raw identifier - the units slice is not part of this module. */
  @Column(name = "unit_id", nullable = false)
  private UUID unitId;

  /** The party renting. Raw identifier - the tenants slice is not part of this module. */
  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  /**
   * The owner letting the unit. Nullable: some stock is let on a firm's own account, and a missing
   * landlord must not block a lease from existing.
   */
  @Column(name = "primary_owner_id")
  private UUID primaryOwnerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private LeaseStatus status = LeaseStatus.DRAFT;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

  @Column(name = "rent_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal rentAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "rent_frequency", nullable = false, length = 20)
  private RentFrequency rentFrequency = RentFrequency.MONTHLY;

  @Column(name = "due_day", nullable = false)
  private int dueDay = 5;

  @Column(name = "escalation_percent", precision = 5, scale = 2)
  private BigDecimal escalationPercent;

  @Column(name = "deposit_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal depositAmount = BigDecimal.ZERO;

  @Column(name = "deposit_received_at")
  private LocalDateTime depositReceivedAt;

  @Column(name = "deposit_refunded_amount", precision = 12, scale = 2)
  private BigDecimal depositRefundedAmount;

  @Column(name = "deposit_refunded_at")
  private LocalDateTime depositRefundedAt;

  @Column(name = "notice_period_days", nullable = false)
  private int noticePeriodDays = 30;

  @Column(name = "agreement_reference", length = 120)
  private String agreementReference;

  @Column(name = "signed_at")
  private LocalDateTime signedAt;

  @Column(name = "activated_at")
  private LocalDateTime activatedAt;

  @Column(name = "terminated_at")
  private LocalDateTime terminatedAt;

  @Column(name = "termination_reason", length = 500)
  private String terminationReason;

  /** The lease this one replaced, set on a renewal. The reverse of the {@code RENEWED} state. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "renewal_of_lease_id")
  private Lease renewalOfLease;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getLeaseCode() {
    return leaseCode;
  }

  public void setLeaseCode(String leaseCode) {
    this.leaseCode = leaseCode;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public UUID getUnitId() {
    return unitId;
  }

  public void setUnitId(UUID unitId) {
    this.unitId = unitId;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public UUID getPrimaryOwnerId() {
    return primaryOwnerId;
  }

  public void setPrimaryOwnerId(UUID primaryOwnerId) {
    this.primaryOwnerId = primaryOwnerId;
  }

  public LeaseStatus getStatus() {
    return status;
  }

  public void setStatus(LeaseStatus status) {
    this.status = status;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public void setEndDate(LocalDate endDate) {
    this.endDate = endDate;
  }

  public BigDecimal getRentAmount() {
    return rentAmount;
  }

  public void setRentAmount(BigDecimal rentAmount) {
    this.rentAmount = rentAmount;
  }

  public RentFrequency getRentFrequency() {
    return rentFrequency;
  }

  public void setRentFrequency(RentFrequency rentFrequency) {
    this.rentFrequency = rentFrequency;
  }

  public int getDueDay() {
    return dueDay;
  }

  public void setDueDay(int dueDay) {
    this.dueDay = dueDay;
  }

  public BigDecimal getEscalationPercent() {
    return escalationPercent;
  }

  public void setEscalationPercent(BigDecimal escalationPercent) {
    this.escalationPercent = escalationPercent;
  }

  public BigDecimal getDepositAmount() {
    return depositAmount;
  }

  public void setDepositAmount(BigDecimal depositAmount) {
    this.depositAmount = depositAmount;
  }

  public LocalDateTime getDepositReceivedAt() {
    return depositReceivedAt;
  }

  public void setDepositReceivedAt(LocalDateTime depositReceivedAt) {
    this.depositReceivedAt = depositReceivedAt;
  }

  public BigDecimal getDepositRefundedAmount() {
    return depositRefundedAmount;
  }

  public void setDepositRefundedAmount(BigDecimal depositRefundedAmount) {
    this.depositRefundedAmount = depositRefundedAmount;
  }

  public LocalDateTime getDepositRefundedAt() {
    return depositRefundedAt;
  }

  public void setDepositRefundedAt(LocalDateTime depositRefundedAt) {
    this.depositRefundedAt = depositRefundedAt;
  }

  public int getNoticePeriodDays() {
    return noticePeriodDays;
  }

  public void setNoticePeriodDays(int noticePeriodDays) {
    this.noticePeriodDays = noticePeriodDays;
  }

  public String getAgreementReference() {
    return agreementReference;
  }

  public void setAgreementReference(String agreementReference) {
    this.agreementReference = agreementReference;
  }

  public LocalDateTime getSignedAt() {
    return signedAt;
  }

  public void setSignedAt(LocalDateTime signedAt) {
    this.signedAt = signedAt;
  }

  public LocalDateTime getActivatedAt() {
    return activatedAt;
  }

  public void setActivatedAt(LocalDateTime activatedAt) {
    this.activatedAt = activatedAt;
  }

  public LocalDateTime getTerminatedAt() {
    return terminatedAt;
  }

  public void setTerminatedAt(LocalDateTime terminatedAt) {
    this.terminatedAt = terminatedAt;
  }

  public String getTerminationReason() {
    return terminationReason;
  }

  public void setTerminationReason(String terminationReason) {
    this.terminationReason = terminationReason;
  }

  public Lease getRenewalOfLease() {
    return renewalOfLease;
  }

  public void setRenewalOfLease(Lease renewalOfLease) {
    this.renewalOfLease = renewalOfLease;
  }
}
