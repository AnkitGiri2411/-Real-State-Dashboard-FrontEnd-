package com.meridian.realestate.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard API contracts, with every formula stated.
 *
 * <p>A dashboard is only worth having if its figures are defined. Each KPI below names its
 * numerator and its denominator, and {@code DashboardService} computes exactly that, so a number on
 * the screen can be argued with, reproduced in SQL, and reconciled against the rows it came from.
 * Nothing here is a cached aggregate and nothing is seeded: every figure is read from the
 * operational tables on the request that asks for it.</p>
 *
 * <h2>The formulas</h2>
 *
 * <pre>
 *   occupancy %            occupied leasable units / leasable units x 100
 *   monthly rent roll      the contractual rent summed across every running lease
 *                          (ACTIVE + EXPIRING), as billed at each lease's own frequency
 *   outstanding rent       SUM(total_amount - credited_amount - settled) over every non-void
 *                          invoice raised to date
 *   overdue rent           the same outstanding figure, over the invoices whose due date is
 *                          strictly before (today - app.portfolio.rent-overdue-grace-days)
 *   collected this period  the INBOUND, PAID receipts received within the current calendar month
 *   operating expenses     SUM(total_amount) of expenses under a non-capital category, this month
 *   capital expenses       the same, under a capital category - reported, never subtracted
 *   NOI                    collected this period - operating expenses this period
 *   yield %                (monthly rent roll x 12) / total current valuation x 100
 * </pre>
 *
 * <p><b>Zero denominators.</b> Every ratio above returns <b>zero</b> when its denominator is zero,
 * never {@code NaN} and never {@code Infinity}. A portfolio with no leasable units has an occupancy
 * of 0%, not an undefined one, and a dashboard that renders "NaN%" or a chart that refuses to draw
 * is a worse answer than an honest zero. The raw numerators and denominators are returned alongside
 * every ratio so the zero can be told apart from a real measurement.</p>
 *
 * <p><b>NOI, deposits and capital.</b> Net operating income is collected rent minus operating
 * expenses. Capital expenditure is <em>excluded</em> and reported separately (see
 * {@link #capitalExpensesThisPeriod()}), never netted off; deposits are excluded entirely because a
 * deposit held is a liability on the lease rather than a row in the receipts ledger, so the
 * collections figure cannot accidentally include one.</p>
 */
public final class DashboardDtos {

  private DashboardDtos() {}

  /** The whole dashboard, for one as-of date (today unless a caller passes one). */
  public record Summary(
      LocalDate asOf,
      LocalDateTime generatedAt,
      String currency,
      String currencySymbol,
      PortfolioSummary portfolio,
      List<UnitStateCount> unitStates,
      List<TrendPoint> trend,
      List<PropertyPerformance> topPropertiesByValuation,
      List<PropertyPerformance> topPropertiesByRent,
      LeadKpi leads,
      ListingKpi listings,
      ViewingKpi viewings,
      ApplicationKpi applications,
      SalesKpi sales,
      LeaseAttention leases,
      MaintenanceKpi maintenance,
      List<ActivityEntry> recentActivity) {}

  /**
   * The portfolio header.
   *
   * <p>The ratio figures travel with their parts: a screen can say "42 of 60 leasable units" rather
   * than 70% of an unknown whole.</p>
   */
  public record PortfolioSummary(
      long propertyCount,
      long unitCount,
      long leasableUnits,
      long occupiedUnits,
      int occupancyPercent,
      int occupancyTargetPercent,
      BigDecimal totalValuation,
      BigDecimal totalAcquisitionValue,
      BigDecimal monthlyRentRoll,
      BigDecimal outstandingRent,
      long outstandingInvoiceCount,
      BigDecimal overdueRent,
      long overdueInvoiceCount,
      BigDecimal invoicedThisPeriod,
      BigDecimal collectedThisPeriod,
      BigDecimal operatingExpensesThisPeriod,
      BigDecimal capitalExpensesThisPeriod,
      BigDecimal netOperatingIncome,
      BigDecimal annualisedYieldPercent) {}

  /** One bar of the unit-state distribution. */
  public record UnitStateCount(String status, long count) {}

  /** One month of the portfolio trend. */
  public record TrendPoint(
      String month,
      BigDecimal invoiced,
      BigDecimal collected,
      BigDecimal outstanding,
      BigDecimal operatingExpenses,
      BigDecimal netOperatingIncome) {}

  /** One property's standing, for the performance ranking. */
  public record PropertyPerformance(
      UUID propertyId,
      String propertyCode,
      String name,
      String city,
      BigDecimal valuation,
      BigDecimal monthlyRent,
      long leasableUnits,
      long occupiedUnits,
      int occupancyPercent) {}

  /** The lead funnel and the value still being worked. */
  public record LeadKpi(
      long total, long openCount, BigDecimal openPipelineValue, List<StageCount> funnel) {}

  /** Listing activity: how many listings are on the market, stale, and in each state. */
  public record ListingKpi(
      long total, long live, long stale, List<StatusCount> byStatus) {}

  /** The viewing diary and its conversion. */
  public record ViewingKpi(
      long total,
      long scheduled,
      long confirmed,
      long completed,
      long cancelled,
      long noShow,
      int conversionPercent) {}

  /** The application pipeline. */
  public record ApplicationKpi(
      long total, long openCount, List<StatusCount> byStatus) {}

  /** The sales pipeline. */
  public record SalesKpi(
      long total, BigDecimal openPipelineValue, List<StageCount> byStage) {}

  /** The leases that need a decision. */
  public record LeaseAttention(
      long runningLeases,
      long expiringWithinWarningDays,
      long expiringWithinUrgentDays,
      long renewalCandidates,
      long alreadyExpired) {}

  /** The state of the stock's condition. */
  public record MaintenanceKpi(
      long openCount,
      long urgentOpenCount,
      BigDecimal costThisPeriod,
      List<PriorityCount> byPriority,
      List<MonthlyAmount> costTrend) {}

  /** One bar of the maintenance priority chart. */
  public record PriorityCount(String priority, long count) {}

  /** One month of a simple amount series (the maintenance cost trend). */
  public record MonthlyAmount(String month, BigDecimal amount) {}

  /** One rung of a pipeline funnel: a stage and how many records sit in it. */
  public record StageCount(String stage, long count) {}

  /** One bar of a status chart. */
  public record StatusCount(String status, long count) {}

  /** One row of the recent-activity rail. */
  public record ActivityEntry(
      UUID id,
      String module,
      String action,
      String severity,
      String description,
      String actorName,
      String entityType,
      String entityLabel,
      LocalDateTime createdAt) {}
}
