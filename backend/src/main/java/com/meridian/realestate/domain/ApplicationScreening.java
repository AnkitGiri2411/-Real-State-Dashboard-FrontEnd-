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
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One check run on a rental application - identity, employment, income, a landlord's reference.
 *
 * <p>Screening results are separate rows rather than columns on the application, because a firm runs
 * several distinct checks and each has its own outcome, its own evidence and its own date. The
 * schema allows one row per check type per application, and a {@code WAIVED} check is an explicit
 * decision that records who made it rather than an absence of a check.</p>
 */
@Entity
@Table(name = "application_screenings")
public class ApplicationScreening extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "application_id", nullable = false)
  private RentalApplication application;

  @Enumerated(EnumType.STRING)
  @Column(name = "check_type", nullable = false, length = 30)
  private ScreeningCheckType checkType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ScreeningStatus status = ScreeningStatus.PENDING;

  @Column(name = "score")
  private Integer score;

  @Column(name = "notes", length = 1000)
  private String notes;

  /** Where the evidence lives - a file reference or a provider's case id. */
  @Column(name = "evidence_reference", length = 255)
  private String evidenceReference;

  /** Who ran the check. Required unless the status is {@code PENDING}. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "performed_by")
  private User performedBy;

  @Column(name = "performed_at")
  private LocalDateTime performedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public RentalApplication getApplication() {
    return application;
  }

  public void setApplication(RentalApplication application) {
    this.application = application;
  }

  public ScreeningCheckType getCheckType() {
    return checkType;
  }

  public void setCheckType(ScreeningCheckType checkType) {
    this.checkType = checkType;
  }

  public ScreeningStatus getStatus() {
    return status;
  }

  public void setStatus(ScreeningStatus status) {
    this.status = status;
  }

  public Integer getScore() {
    return score;
  }

  public void setScore(Integer score) {
    this.score = score;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public String getEvidenceReference() {
    return evidenceReference;
  }

  public void setEvidenceReference(String evidenceReference) {
    this.evidenceReference = evidenceReference;
  }

  public User getPerformedBy() {
    return performedBy;
  }

  public void setPerformedBy(User performedBy) {
    this.performedBy = performedBy;
  }

  public LocalDateTime getPerformedAt() {
    return performedAt;
  }

  public void setPerformedAt(LocalDateTime performedAt) {
    this.performedAt = performedAt;
  }
}
