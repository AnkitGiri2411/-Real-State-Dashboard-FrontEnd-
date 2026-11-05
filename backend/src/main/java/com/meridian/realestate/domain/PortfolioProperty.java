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
import java.time.LocalDate;
import java.util.UUID;

/**
 * One property's membership of one portfolio, held as a period rather than a flag.
 *
 * <p>{@code allocatedAt} and {@code deallocatedAt} say when the asset entered the book and, for a
 * disposed asset, when it left. Dated rather than deleted, so a portfolio's composition at a past
 * date is reconstructable - which is the question an auditor asks, and the one a boolean
 * {@code isMember} column cannot answer.</p>
 *
 * <p><strong>An open allocation is single-valued per property.</strong> The schema carries a
 * partial unique index on {@code property_id} where {@code deallocated_at is null} and a second
 * one over the live rows, so a property can belong to at most one portfolio and cannot be open in
 * two allocations at once. That is deliberate, not restrictive: a property in two portfolios would
 * double-count in every portfolio-level total, and the error would be silent because each book's
 * own report would still look internally consistent. A co-invested asset is modelled as its own
 * portfolio, with the co-investment expressed through ownership shares.</p>
 *
 * <p>A property in no portfolio is the normal state for a newly acquired asset.</p>
 */
@Entity
@Table(name = "portfolio_properties")
public class PortfolioProperty extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "portfolio_id", nullable = false)
  private Portfolio portfolio;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  @Column(name = "allocated_at", nullable = false)
  private LocalDate allocatedAt;

  /** Null while the allocation is open; set when the asset leaves the book. */
  @Column(name = "deallocated_at")
  private LocalDate deallocatedAt;

  @Column(name = "notes", length = 500)
  private String notes;

  /** True while the asset is currently in the book, rather than having left it. */
  public boolean isOpen() {
    return deallocatedAt == null;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Portfolio getPortfolio() {
    return portfolio;
  }

  public void setPortfolio(Portfolio portfolio) {
    this.portfolio = portfolio;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public LocalDate getAllocatedAt() {
    return allocatedAt;
  }

  public void setAllocatedAt(LocalDate allocatedAt) {
    this.allocatedAt = allocatedAt;
  }

  public LocalDate getDeallocatedAt() {
    return deallocatedAt;
  }

  public void setDeallocatedAt(LocalDate deallocatedAt) {
    this.deallocatedAt = deallocatedAt;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
