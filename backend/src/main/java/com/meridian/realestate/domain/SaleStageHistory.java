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
 * One recorded transition in a sale's stage, oldest first per sale.
 *
 * <p><b>No audit base class, and deliberately so.</b> This table is an append-only trail: it carries
 * {@code changed_at} / {@code changed_by}, which are the audit, and it has no {@code created_at},
 * {@code updated_at}, {@code updated_by} or {@code deleted_at}. A history row is a fact about the past
 * and is never edited or soft-deleted, so bending it to fit {@link Auditable} would have meant columns
 * the schema does not have.</p>
 *
 * <p>{@code from_stage} is nullable because the first row of a sale's history has no prior stage. Both
 * stages are {@link SaleStage}, the same domain type as {@code sales_opportunities.stage}, so a
 * transition cannot name a stage the sale itself could not be in.</p>
 *
 * <p>The trail is what makes the funnel and the velocity figures defensible. The current stage says
 * where a deal is; the sequence of stages with the time between them says how it got there and how
 * long it took, which is what a pipeline review actually argues about. It is also the only remaining
 * record of a stage a deal passed through once the row moved on, which is why a stage change must
 * write a row here in the same transaction as the change itself.</p>
 */
@Entity
@Table(name = "sale_stage_history")
public class SaleStageHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sale_id", nullable = false)
  private SalesOpportunity sale;

  /** The stage left; null for the first entry in a sale's history. */
  @Enumerated(EnumType.STRING)
  @Column(name = "from_stage", length = 20)
  private SaleStage fromStage;

  /** The stage entered. */
  @Enumerated(EnumType.STRING)
  @Column(name = "to_stage", nullable = false, length = 20)
  private SaleStage toStage;

  @Column(name = "reason", length = 500)
  private String reason;

  /** Who made the change. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "changed_by")
  private User changedBy;

  @Column(name = "changed_at", nullable = false)
  private LocalDateTime changedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public SalesOpportunity getSale() {
    return sale;
  }

  public void setSale(SalesOpportunity sale) {
    this.sale = sale;
  }

  public SaleStage getFromStage() {
    return fromStage;
  }

  public void setFromStage(SaleStage fromStage) {
    this.fromStage = fromStage;
  }

  public SaleStage getToStage() {
    return toStage;
  }

  public void setToStage(SaleStage toStage) {
    this.toStage = toStage;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public User getChangedBy() {
    return changedBy;
  }

  public void setChangedBy(User changedBy) {
    this.changedBy = changedBy;
  }

  public LocalDateTime getChangedAt() {
    return changedAt;
  }

  public void setChangedAt(LocalDateTime changedAt) {
    this.changedAt = changedAt;
  }
}
