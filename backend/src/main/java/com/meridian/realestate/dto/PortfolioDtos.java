package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Portfolio;
import com.meridian.realestate.domain.PortfolioProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The portfolio register's API contract: the books of assets, their allocations, and the performance
 * a book is reported on.
 *
 * <p><b>Why these records exist.</b> A {@code Portfolio} entity carries lazy owner and manager
 * associations and the housekeeping columns, and never leaves the service. Each record here is the
 * deliberate subset the register promises, with the enum flattened to its name.</p>
 *
 * <p><b>The rule these records protect: a target yield is a target, not an achieved one.</b> A
 * portfolio's {@code targetYieldPercent} is the number the book is managed against - nullable, because
 * a development book has no income target and a zero would be a claim rather than an absence - and it
 * is never presented as a figure that was achieved. The <em>achieved</em> yield is derived from the
 * book's own rent and valuation and appears beside the target, never in place of it. In {@link
 * PortfolioPerformance} the two are separate fields, and the target is described as a target, so the
 * distinction cannot be lost in rendering.</p>
 *
 * <p><b>Money is {@link BigDecimal} only.</b> No amount here is a {@code double}. The performance
 * figures are declared at the scale the schema stores and are rounded by {@code MoneyPolicy} where
 * they are computed.</p>
 */
public final class PortfolioDtos {

  private PortfolioDtos() {}

  /** The closed set of strategies {@code ck_portfolios_strategy} allows. */
  private static final String STRATEGY_SET =
      "CORE|CORE_PLUS|VALUE_ADD|OPPORTUNISTIC|DEVELOPMENT|INCOME|TRADING";

  /** The closed set of statuses {@code ck_portfolios_status} allows. */
  private static final String STATUS_SET = "ACTIVE|CLOSED";

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** One row of the portfolio register: enough to identify a book and read its headline figures. */
  public record PortfolioSummary(
      UUID id,
      String portfolioCode,
      String name,
      String description,
      String strategy,
      String status,
      UUID ownerId,
      String ownerName,
      UUID managerId,
      String managerName,
      BigDecimal targetYieldPercent,
      LocalDate inceptionDate,
      long propertyCount,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static PortfolioSummary from(Portfolio portfolio, long propertyCount) {
      return new PortfolioSummary(
          portfolio.getId(),
          portfolio.getPortfolioCode(),
          portfolio.getName(),
          portfolio.getDescription(),
          portfolio.getStrategy() == null ? null : portfolio.getStrategy().name(),
          portfolio.getStatus() == null ? null : portfolio.getStatus().name(),
          portfolio.getOwner() == null ? null : portfolio.getOwner().getId(),
          portfolio.getOwner() == null ? null : portfolio.getOwner().getDisplayName(),
          portfolio.getManager() == null ? null : portfolio.getManager().getId(),
          portfolio.getManager() == null ? null : portfolio.getManager().getFullName(),
          portfolio.getTargetYieldPercent(),
          portfolio.getInceptionDate(),
          propertyCount,
          portfolio.getCreatedAt(),
          portfolio.getUpdatedAt());
    }
  }

  /** One book's full record, with the assets currently allocated to it. */
  public record PortfolioDetail(PortfolioSummary portfolio, java.util.List<AllocationEntry> allocations) {}

  /**
   * One asset's membership of a book, held as a period.
   *
   * <p>{@code allocatedAt} and {@code deallocatedAt} say when the asset entered the book and, for a
   * disposed asset, when it left. An open allocation has no deallocation date, and {@code open} says
   * so without the caller having to compare dates that may both be present.</p>
   */
  public record AllocationEntry(
      UUID id,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      LocalDate allocatedAt,
      LocalDate deallocatedAt,
      boolean open,
      String notes) {

    public static AllocationEntry from(PortfolioProperty allocation) {
      return new AllocationEntry(
          allocation.getId(),
          allocation.getProperty() == null ? null : allocation.getProperty().getId(),
          allocation.getProperty() == null ? null : allocation.getProperty().getPropertyCode(),
          allocation.getProperty() == null ? null : allocation.getProperty().getName(),
          allocation.getAllocatedAt(),
          allocation.getDeallocatedAt(),
          allocation.isOpen(),
          allocation.getNotes());
    }
  }

  /**
   * One book's performance, every figure read from the portfolio's own query.
   *
   * <p><b>Net operating income.</b> Collected rent, less operating expenses, with capital expenditure
   * excluded and reported separately - the rule the {@code V15} header states. The operating and
   * capital figures are read from the expense ledger's own category split
   * ({@code expense_categories.is_capital}); they are not two views of one number and must never be
   * added together. Capital expenditure improves the asset rather than costing the period's letting,
   * so it is reported on its own line and is deliberately <em>not</em> netted off the running result -
   * netting it off would overstate cost and understate what the book actually earned.</p>
   *
   * <p><b>Achieved yield and the target are different things.</b> {@code achievedYieldPercent} is
   * derived - the book's annualised rent over its current valuation - while {@code targetYieldPercent}
   * is the number the book is managed against. They sit side by side and the target is a target.</p>
   *
   * <p><b>Every figure is guarded.</b> A book with no valuation reads {@code 0.00} achieved yield, not
   * {@code NaN} and not an exception, because a book that holds land rather than income is a real
   * state; a book with no leasable units reads {@code 0%} occupancy for the same reason.</p>
   */
  public record PortfolioPerformance(
      UUID portfolioId,
      String portfolioCode,
      String name,
      String strategy,
      String status,
      LocalDate asOf,
      long propertyCount,
      BigDecimal totalValuation,
      BigDecimal totalAcquisitionValue,
      BigDecimal monthlyRentRoll,
      BigDecimal operatingExpenses,
      BigDecimal capitalExpenses,
      BigDecimal netOperatingIncome,
      BigDecimal annualisedRent,
      BigDecimal achievedYieldPercent,
      BigDecimal targetYieldPercent,
      int occupancyPercent,
      int occupancyTargetPercent,
      String yieldBasis) {}

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /**
   * Create a portfolio.
   *
   * <p>{@code status} defaults to {@code ACTIVE}. A book created {@code CLOSED} must carry a closing
   * note, which the schema requires and the service enforces, so a closed book cannot silently
   * re-enter the firm's live totals without an explanation.</p>
   */
  public record CreatePortfolioRequest(
      @NotBlank @Size(max = 200) String name,
      String description,
      UUID ownerId,
      UUID managerId,
      @Pattern(regexp = STRATEGY_SET, message = "must be a portfolio strategy") String strategy,
      @DecimalMin(value = "0", message = "must be between 0 and 100")
          @DecimalMax(value = "100", message = "must be between 0 and 100")
          BigDecimal targetYieldPercent,
      LocalDate inceptionDate,
      @Pattern(regexp = STATUS_SET, message = "must be a portfolio status") String status,
      @Size(max = 1000) String notes) {}

  /** Change a portfolio. Every field is optional; only the ones supplied are applied. */
  public record UpdatePortfolioRequest(
      @Size(max = 200) String name,
      String description,
      UUID ownerId,
      UUID managerId,
      @Pattern(regexp = STRATEGY_SET, message = "must be a portfolio strategy") String strategy,
      @DecimalMin(value = "0", message = "must be between 0 and 100")
          @DecimalMax(value = "100", message = "must be between 0 and 100")
          BigDecimal targetYieldPercent,
      LocalDate inceptionDate,
      @Pattern(regexp = STATUS_SET, message = "must be a portfolio status") String status,
      @Size(max = 1000) String notes) {}

  /**
   * Allocate a property to a book.
   *
   * <p>{@code allocatedAt} defaults to today. A property belongs to at most one book at a time - the
   * schema enforces it with a partial unique index - so a property already open in any book is refused
   * rather than silently double-counted.</p>
   */
  public record AllocatePropertyRequest(
      @NotNull UUID propertyId, LocalDate allocatedAt, LocalDate deallocatedAt, @Size(max = 500) String notes) {}

  /** The strategy filter set, exposed so a caller can be told the accepted values. */
  public static java.util.List<String> strategyValues() {
    return java.util.List.of(STRATEGY_SET.split("\\|"));
  }
}
