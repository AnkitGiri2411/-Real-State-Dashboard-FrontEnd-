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
 * A formal request for a specific unit, with the terms the applicant proposes and the circumstances
 * the decision is made on.
 *
 * <p><strong>At most one open application per unit and applicant.</strong> The schema enforces this
 * with the partial unique index {@code uq_rental_applications_open_on_unit} over
 * {@code (unit_id, applicant_email)} for the open statuses. The service must check for an existing
 * open application before inserting and surface a conflict as an HTTP {@code 409}, rather than
 * letting the unique index fail opaquely - a raw constraint violation tells the caller nothing about
 * which rule it broke, and two open rows would let the same applicant be approved twice for one
 * flat.</p>
 *
 * <p>The decision is recorded whole: the {@code ck_rental_applications_decision} constraint refuses
 * an approval or rejection without both a decider and a decision time, and refuses a rejection
 * without a reason. The terms the applicant asks for are kept apart from the lease that follows,
 * because what was applied for and what was signed genuinely differ.</p>
 */
@Entity
@Table(name = "rental_applications")
public class RentalApplication extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "application_code", nullable = false, length = 30, unique = true)
  private String applicationCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "unit_id", nullable = false)
  private Unit unit;

  /** The lead the applicant came from, when they are already known to the firm. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lead_id")
  private Lead lead;

  /** The tenant record the applicant maps to, when one exists. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tenant_id")
  private Tenant tenant;

  @Enumerated(EnumType.STRING)
  @Column(name = "applicant_type", nullable = false, length = 20)
  private ApplicantType applicantType = ApplicantType.INDIVIDUAL;

  @Column(name = "applicant_name", nullable = false, length = 200)
  private String applicantName;

  @Column(name = "applicant_email", length = 255)
  private String applicantEmail;

  @Column(name = "applicant_phone", length = 30)
  private String applicantPhone;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ApplicationStatus status = ApplicationStatus.SUBMITTED;

  @Column(name = "proposed_start_date")
  private LocalDate proposedStartDate;

  @Column(name = "proposed_end_date")
  private LocalDate proposedEndDate;

  @Column(name = "proposed_rent", precision = 12, scale = 2)
  private BigDecimal proposedRent;

  @Column(name = "proposed_deposit", precision = 12, scale = 2)
  private BigDecimal proposedDeposit;

  @Column(name = "occupancy_count")
  private Integer occupancyCount;

  @Column(name = "has_pets", nullable = false)
  private boolean hasPets = false;

  @Enumerated(EnumType.STRING)
  @Column(name = "employment_type", length = 30)
  private EmploymentType employmentType;

  @Column(name = "annual_income", precision = 14, scale = 2)
  private BigDecimal annualIncome;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  @Column(name = "submitted_at", nullable = false)
  private LocalDateTime submittedAt;

  /** Set together with {@code decidedBy} and the status when a decision is taken; see the class note. */
  @Column(name = "decided_at")
  private LocalDateTime decidedAt;

  /** Who decided. Required on an approval or rejection; see the class note. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "decided_by")
  private User decidedBy;

  @Column(name = "decision_notes", length = 1000)
  private String decisionNotes;

  /** Required on a rejection; see the class note. */
  @Column(name = "rejection_reason", length = 500)
  private String rejectionReason;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getApplicationCode() {
    return applicationCode;
  }

  public void setApplicationCode(String applicationCode) {
    this.applicationCode = applicationCode;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Unit getUnit() {
    return unit;
  }

  public void setUnit(Unit unit) {
    this.unit = unit;
  }

  public Lead getLead() {
    return lead;
  }

  public void setLead(Lead lead) {
    this.lead = lead;
  }

  public Tenant getTenant() {
    return tenant;
  }

  public void setTenant(Tenant tenant) {
    this.tenant = tenant;
  }

  public ApplicantType getApplicantType() {
    return applicantType;
  }

  public void setApplicantType(ApplicantType applicantType) {
    this.applicantType = applicantType;
  }

  public String getApplicantName() {
    return applicantName;
  }

  public void setApplicantName(String applicantName) {
    this.applicantName = applicantName;
  }

  public String getApplicantEmail() {
    return applicantEmail;
  }

  public void setApplicantEmail(String applicantEmail) {
    this.applicantEmail = applicantEmail;
  }

  public String getApplicantPhone() {
    return applicantPhone;
  }

  public void setApplicantPhone(String applicantPhone) {
    this.applicantPhone = applicantPhone;
  }

  public ApplicationStatus getStatus() {
    return status;
  }

  public void setStatus(ApplicationStatus status) {
    this.status = status;
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

  public BigDecimal getProposedRent() {
    return proposedRent;
  }

  public void setProposedRent(BigDecimal proposedRent) {
    this.proposedRent = proposedRent;
  }

  public BigDecimal getProposedDeposit() {
    return proposedDeposit;
  }

  public void setProposedDeposit(BigDecimal proposedDeposit) {
    this.proposedDeposit = proposedDeposit;
  }

  public Integer getOccupancyCount() {
    return occupancyCount;
  }

  public void setOccupancyCount(Integer occupancyCount) {
    this.occupancyCount = occupancyCount;
  }

  public boolean isHasPets() {
    return hasPets;
  }

  public void setHasPets(boolean hasPets) {
    this.hasPets = hasPets;
  }

  public EmploymentType getEmploymentType() {
    return employmentType;
  }

  public void setEmploymentType(EmploymentType employmentType) {
    this.employmentType = employmentType;
  }

  public BigDecimal getAnnualIncome() {
    return annualIncome;
  }

  public void setAnnualIncome(BigDecimal annualIncome) {
    this.annualIncome = annualIncome;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public LocalDateTime getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(LocalDateTime submittedAt) {
    this.submittedAt = submittedAt;
  }

  public LocalDateTime getDecidedAt() {
    return decidedAt;
  }

  public void setDecidedAt(LocalDateTime decidedAt) {
    this.decidedAt = decidedAt;
  }

  public User getDecidedBy() {
    return decidedBy;
  }

  public void setDecidedBy(User decidedBy) {
    this.decidedBy = decidedBy;
  }

  public String getDecisionNotes() {
    return decisionNotes;
  }

  public void setDecisionNotes(String decisionNotes) {
    this.decisionNotes = decisionNotes;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public void setRejectionReason(String rejectionReason) {
    this.rejectionReason = rejectionReason;
  }
}
