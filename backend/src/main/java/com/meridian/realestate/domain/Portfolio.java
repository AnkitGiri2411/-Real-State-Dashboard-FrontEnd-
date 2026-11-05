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
 * A book of assets held to a single strategy and reported as one.
 *
 * <p>It is what makes "portfolio value", "portfolio yield" and "portfolio occupancy" meaningful
 * questions rather than aggregates over everything the firm has ever touched. The members are the
 * allocations in {@link PortfolioProperty}, dated rather than flagged so the book's composition at
 * a past date is reconstructable.</p>
 *
 * <p><strong>{@code targetYieldPercent} is a target, not an achieved yield.</strong> It is the
 * number the book is managed against, which is why it is nullable rather than defaulted to zero: a
 * development book has no income target, and a zero would be a claim rather than an absence. The
 * yield actually achieved is <em>derived</em> - rent income over current valuation - and is not
 * stored anywhere, so there is no second, stale copy of it to disagree with the books. The
 * CHECK constrains the target to 0-100 because a target outside that range is a unit error rather
 * than a bold ambition.</p>
 *
 * <p>Both {@code owner} and {@code manager} are optional: a firm's own trading book has no external
 * owner, and a book can exist before anyone is named as its manager.</p>
 */
@Entity
@Table(name = "portfolios")
public class Portfolio extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "portfolio_code", nullable = false, length = 30, unique = true)
  private String portfolioCode;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "description", columnDefinition = "text")
  private String description;

  /** Who the book is held for; null for the firm's own book. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id")
  private Owner owner;

  /** Who runs the book. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "manager_id")
  private User manager;

  @Enumerated(EnumType.STRING)
  @Column(name = "strategy", nullable = false, length = 30)
  private PortfolioStrategy strategy = PortfolioStrategy.CORE;

  /** The yield the book is managed against - a target, never an achieved figure. */
  @Column(name = "target_yield_percent", precision = 5, scale = 2)
  private BigDecimal targetYieldPercent;

  @Column(name = "inception_date")
  private LocalDate inceptionDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PortfolioStatus status = PortfolioStatus.ACTIVE;

  /** Doubles as the closing note the schema requires on a closed book. */
  @Column(name = "notes", length = 1000)
  private String notes;

  /** True when the book is managed against a yield target, rather than merely holding assets. */
  public boolean hasYieldTarget() {
    return targetYieldPercent != null;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getPortfolioCode() {
    return portfolioCode;
  }

  public void setPortfolioCode(String portfolioCode) {
    this.portfolioCode = portfolioCode;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Owner getOwner() {
    return owner;
  }

  public void setOwner(Owner owner) {
    this.owner = owner;
  }

  public User getManager() {
    return manager;
  }

  public void setManager(User manager) {
    this.manager = manager;
  }

  public PortfolioStrategy getStrategy() {
    return strategy;
  }

  public void setStrategy(PortfolioStrategy strategy) {
    this.strategy = strategy;
  }

  public BigDecimal getTargetYieldPercent() {
    return targetYieldPercent;
  }

  public void setTargetYieldPercent(BigDecimal targetYieldPercent) {
    this.targetYieldPercent = targetYieldPercent;
  }

  public LocalDate getInceptionDate() {
    return inceptionDate;
  }

  public void setInceptionDate(LocalDate inceptionDate) {
    this.inceptionDate = inceptionDate;
  }

  public PortfolioStatus getStatus() {
    return status;
  }

  public void setStatus(PortfolioStatus status) {
    this.status = status;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
