package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.AuditLog;
import com.meridian.realestate.domain.ApplicationStatus;
import com.meridian.realestate.domain.InvoiceStatus;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.LeaseStatus;
import com.meridian.realestate.domain.LeadStage;
import com.meridian.realestate.domain.Listing;
import com.meridian.realestate.domain.ListingStatus;
import com.meridian.realestate.domain.MaintenancePriority;
import com.meridian.realestate.domain.MaintenanceStatus;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.RentInvoice;
import com.meridian.realestate.domain.SaleStage;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.domain.ViewingStatus;
import com.meridian.realestate.dto.DashboardDtos;
import com.meridian.realestate.repository.AuditLogRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.ListingRepository;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.PaymentRepository;
import com.meridian.realestate.repository.PropertyExpenseRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.RentInvoiceRepository;
import com.meridian.realestate.repository.RentalApplicationRepository;
import com.meridian.realestate.repository.SalesOpportunityRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.ViewingRepository;
import com.meridian.realestate.web.PageRequestFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard's figures.
 *
 * <p>Every KPI here is computed from the operational tables - the properties, the units, the leases,
 * the invoices, the receipts, the expenses and the faults - and from nothing else. There is no
 * cached aggregate and no seeded figure: if the dashboard says the portfolio is 71% let, that is
 * what the rows say, and it can be reproduced with SQL by anyone who doubts it.</p>
 *
 * <h2>The period</h2>
 * <p>"This period" on the portfolio header is the <b>current calendar month</b> - from the first of
 * the month to the as-of date - so {@code invoicedThisPeriod}, {@code collectedThisPeriod} and the
 * expense figures all cover the same window as each other. {@code outstandingRent}, {@code
 * overdueRent}, the rent roll and the valuation are <b>point-in-time</b> facts as at the as-of
 * date, not period figures, because a balance and a valuation are not things that happen over a
 * month.</p>
 *
 * <h2>The trend window</h2>
 * <p>The trend covers {@code app.portfolio.trend-months} calendar months ending with the current
 * one. Its {@code outstanding} line is <b>cumulative arrears</b> - the running total of invoiced
 * minus collected from the start of the window to the end of that month - because "what was
 * outstanding in March" is the arrears position at the end of March, which is a cumulative figure
 * rather than a monthly one.</p>
 *
 * <h2>Guarded arithmetic</h2>
 * <p>Every ratio is guarded so a zero denominator yields zero rather than {@code NaN} or an
 * exception: a portfolio with no leasable units is 0% occupied, and a portfolio with no valuation has
 * a 0% yield. The raw parts are always returned beside the ratio so 0-of-0 can be told apart from
 * 0-of-120.</p>
 *
 * <h2>What is deliberately not returned</h2>
 * <p>No figure here is estimated or inferred. Per-day historical occupancy is not returned, because
 * the schema stores a unit's current state and its change history, not a nightly snapshot; where a
 * seller-style "nightly occupancy" would be needed, the honest thing is to omit it rather than
 * invent it. Likewise, a "collected this period" split by payment method is left to the report that
 * owns that question rather than guessed here.</p>
 */
@Service
public class DashboardService {

  /** LocalDateTime-based maintenance and receipt sums want the far past, not the epoch. */
  private static final LocalDate ALL_TIME_FROM = LocalDate.of(1900, 1, 1);

  /** The maintenance states that count as outstanding work - the schema's partial index predicate. */
  private static final List<MaintenanceStatus> OPEN_MAINTENANCE =
      List.of(MaintenanceStatus.OPEN, MaintenanceStatus.ASSIGNED, MaintenanceStatus.IN_PROGRESS);

  /** The priorities that count towards the urgent-work KPI. */
  private static final List<MaintenancePriority> URGENT_PRIORITIES =
      List.of(MaintenancePriority.HIGH, MaintenancePriority.URGENT);

  /** The lead stages that are no longer pipeline. */
  private static final List<LeadStage> CLOSED_LEAD_STAGES = List.of(LeadStage.WON, LeadStage.LOST);

  /** The sale stages that have concluded, either way - the pipeline excludes both. */
  private static final List<SaleStage> CONCLUDED_SALE_STAGES = List.of(SaleStage.CLOSED, SaleStage.LOST);

  /** The listing states that are closed - everything else is on the market. */
  private static final List<ListingStatus> CLOSED_LISTING_STATUSES =
      List.of(ListingStatus.LET_AGREED, ListingStatus.SOLD, ListingStatus.WITHDRAWN, ListingStatus.EXPIRED);

  /** How many rows each ranking returns. */
  private static final int RANKING_SIZE = 5;

  /** How many recent audit entries the activity rail shows. */
  private static final int ACTIVITY_SIZE = 10;

  /**
   * The most pages of past-due invoices scanned when summing overdue rent. A safety valve against an
   * unbounded scan, not a business limit; the count reported is always exact.
   */
  private static final int MAX_OVERDUE_PAGES = 100;

  private final PropertyRepository properties;
  private final UnitRepository units;
  private final LeaseRepository leases;
  private final RentInvoiceRepository invoices;
  private final PaymentRepository payments;
  private final PropertyExpenseRepository expenses;
  private final MaintenanceRequestRepository maintenance;
  private final LeadRepository leads;
  private final ListingRepository listings;
  private final ViewingRepository viewings;
  private final RentalApplicationRepository applications;
  private final SalesOpportunityRepository sales;
  private final AuditLogRepository audit;
  private final AppProperties config;

  public DashboardService(
      PropertyRepository properties,
      UnitRepository units,
      LeaseRepository leases,
      RentInvoiceRepository invoices,
      PaymentRepository payments,
      PropertyExpenseRepository expenses,
      MaintenanceRequestRepository maintenance,
      LeadRepository leads,
      ListingRepository listings,
      ViewingRepository viewings,
      RentalApplicationRepository applications,
      SalesOpportunityRepository sales,
      AuditLogRepository audit,
      AppProperties config) {
    this.properties = properties;
    this.units = units;
    this.leases = leases;
    this.invoices = invoices;
    this.payments = payments;
    this.expenses = expenses;
    this.maintenance = maintenance;
    this.leads = leads;
    this.listings = listings;
    this.viewings = viewings;
    this.applications = applications;
    this.sales = sales;
    this.audit = audit;
    this.config = config;
  }

  /** Everything the dashboard shows, for one as-of date (today when none is given). */
  @Transactional(readOnly = true)
  public DashboardDtos.Summary summary(LocalDate asOf) {
    LocalDate today = asOf == null ? LocalDate.now() : asOf;
    LocalDateTime now = LocalDateTime.now();

    LocalDate periodStart = today.withDayOfMonth(1);
    int trendMonths = config.getPortfolio().getTrendMonths();
    YearMonth trendStart = YearMonth.from(today).minusMonths(trendMonths - 1L);
    YearMonth trendEnd = YearMonth.from(today);

    return new DashboardDtos.Summary(
        today,
        now,
        config.getMoney().getCurrency(),
        config.getMoney().getCurrencySymbol(),
        portfolio(periodStart, today),
        unitStates(),
        trend(trendStart, trendEnd),
        rankings().byValuation(),
        rankings().byRent(),
        leadKpi(),
        listingKpi(today),
        viewingKpi(),
        applicationKpi(),
        salesKpi(),
        leaseAttention(today),
        maintenanceKpi(periodStart, today, trendStart, trendEnd),
        recentActivity());
  }

  // ----------------------------------------------------------------------------------------------
  // The portfolio header
  // ----------------------------------------------------------------------------------------------

  private DashboardDtos.PortfolioSummary portfolio(LocalDate periodStart, LocalDate today) {
    long propertyCount = properties.countByDeletedAtIsNull();
    long unitCount = units.countByDeletedAtIsNull();
    long leasable = units.countByLeasableTrueAndDeletedAtIsNull();
    long occupied = units.countByLeasableTrueAndStatusAndDeletedAtIsNull(UnitStatus.OCCUPIED);

    BigDecimal valuation = money(properties.sumCurrentValuation());
    BigDecimal acquisition = money(properties.sumAcquisitionValue());
    BigDecimal rentRoll = money(leases.sumRunningRentAmount());

    // Outstanding and its parts. The ledger's own aggregate excludes void invoices and derives
    // settlement from PAID allocations, so this agrees with the rent roll screen by construction.
    RentInvoiceRepository.CollectionsPosition allTime =
        invoices.collectionsPosition(ALL_TIME_FROM, today);
    BigDecimal outstandingRent = money(allTime == null ? null : allTime.getOutstanding());

    RentInvoiceRepository.CollectionsPosition period = invoices.collectionsPosition(periodStart, today);
    BigDecimal invoicedThisPeriod = money(period == null ? null : period.getInvoiced());
    BigDecimal collectedThisPeriod = money(period == null ? null : period.getSettled());

    BigDecimal operating = money(expenses.sumOperatingExpenses(periodStart, today));
    BigDecimal capital = money(expenses.sumCapitalExpenses(periodStart, today));
    BigDecimal noi = money(collectedThisPeriod.subtract(operating));

    long issuedInvoices = invoices.countByStatusAndDeletedAtIsNull(InvoiceStatus.ISSUED);

    // Overdue is due-date based and sits strictly past the configured grace period: an invoice is
    // overdue when its due date is before (today - grace). An invoice due exactly on the boundary
    // day is not overdue, which is what the "< asOf" predicate gives.
    LocalDate cutoff = today.minusDays(config.getPortfolio().getRentOverdueGraceDays());
    long overdueCount = 0;
    BigDecimal overdueRent = MoneyPolicy.zero();
    Pageable pageable = PageRequest.of(0, PageRequestFactory.MAX_PAGE_SIZE);
    Page<RentInvoice> pastDue = invoices.findPastDueCollectable(cutoff, pageable);
    if (pastDue != null) {
      overdueCount = pastDue.getTotalElements();
      overdueRent = sumOutstanding(pastDue.getContent());
      int pageNumber = 1;
      while (pastDue.hasNext() && pageNumber < MAX_OVERDUE_PAGES) {
        pastDue =
            invoices.findPastDueCollectable(
                cutoff, PageRequest.of(pageNumber, PageRequestFactory.MAX_PAGE_SIZE));
        overdueRent = overdueRent.add(sumOutstanding(pastDue.getContent()));
        pageNumber++;
      }
    }

    // Annualised yield: the rent roll for a year against the current valuation. Guarded - a
    // portfolio with no recorded valuation reports a 0% yield rather than failing.
    BigDecimal annualRent = rentRoll.multiply(BigDecimal.valueOf(12));
    BigDecimal yield = ratioToPercent(annualRent, valuation);

    return new DashboardDtos.PortfolioSummary(
        propertyCount,
        unitCount,
        leasable,
        occupied,
        percent(occupied, leasable),
        config.getPortfolio().getOccupancyTargetPercent(),
        valuation,
        acquisition,
        rentRoll,
        outstandingRent,
        issuedInvoices,
        overdueRent,
        overdueCount,
        invoicedThisPeriod,
        collectedThisPeriod,
        operating,
        capital,
        noi,
        yield);
  }

  private BigDecimal sumOutstanding(List<RentInvoice> pastDue) {
    BigDecimal total = MoneyPolicy.zero();
    for (RentInvoice invoice : pastDue) {
      BigDecimal outstanding =
          invoices
              .findOutstandingByInvoiceId(invoice.getId())
              .map(RentInvoiceRepository.InvoiceOutstanding::getOutstanding)
              .map(MoneyPolicy::money)
              // The projection is written for every live invoice; the fallback keeps a missing one
              // from silently zeroing the arrears, using the stored parts it can read directly.
              .orElseGet(() -> money(invoice.getTotalAmount().subtract(invoice.getCreditedAmount())));
      total = total.add(outstanding);
    }
    return money(total);
  }

  private List<DashboardDtos.UnitStateCount> unitStates() {
    List<DashboardDtos.UnitStateCount> states = new ArrayList<>();
    for (UnitStatus status : UnitStatus.values()) {
      states.add(new DashboardDtos.UnitStateCount(status.name(), units.countByStatusAndDeletedAtIsNull(status)));
    }
    return states;
  }

  // ----------------------------------------------------------------------------------------------
  // The trend
  // ----------------------------------------------------------------------------------------------

  private List<DashboardDtos.TrendPoint> trend(YearMonth from, YearMonth to) {
    LocalDate windowStart = from.atDay(1);
    LocalDate windowEnd = to.atEndOfMonth();

    Map<YearMonth, RentInvoiceRepository.MonthlyInvoicedVersusCollected> collections = new LinkedHashMap<>();
    for (RentInvoiceRepository.MonthlyInvoicedVersusCollected row :
        invoices.monthlyInvoicedVersusCollected(windowStart, windowEnd)) {
      collections.put(YearMonth.of(row.getPeriodYear(), row.getPeriodMonth()), row);
    }

    List<DashboardDtos.TrendPoint> points = new ArrayList<>();
    BigDecimal arrears = MoneyPolicy.zero();

    for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
      RentInvoiceRepository.MonthlyInvoicedVersusCollected row = collections.get(month);
      BigDecimal invoiced = money(row == null ? null : row.getInvoiced());
      BigDecimal collected = money(row == null ? null : row.getCollected());
      BigDecimal operating = money(expenses.sumOperatingExpenses(month.atDay(1), month.atEndOfMonth()));

      arrears = money(arrears.add(invoiced).subtract(collected));
      points.add(
          new DashboardDtos.TrendPoint(
              month.toString(),
              invoiced,
              collected,
              arrears,
              operating,
              money(collected.subtract(operating))));
    }
    return points;
  }

  // ----------------------------------------------------------------------------------------------
  // Property performance
  // ----------------------------------------------------------------------------------------------

  private record Rankings(
      List<DashboardDtos.PropertyPerformance> byValuation,
      List<DashboardDtos.PropertyPerformance> byRent) {}

  private Rankings rankings() {
    List<Property> properties = this.properties.findAllByDeletedAtIsNull();
    List<Unit> allUnits = units.findAllByDeletedAtIsNull();
    List<Lease> allLeases = leases.findAllByDeletedAtIsNull();

    Map<UUID, long[]> unitCounts = new HashMap<>();
    for (Unit unit : allUnits) {
      if (!unit.isLeasable()) continue;
      long[] counts = unitCounts.computeIfAbsent(unit.getProperty().getId(), key -> new long[2]);
      counts[0]++;
      if (unit.getStatus() == UnitStatus.OCCUPIED) counts[1]++;
    }

    Map<UUID, BigDecimal> rentByProperty = new HashMap<>();
    for (Lease lease : allLeases) {
      if (lease.getStatus() == null || !lease.getStatus().isRunning()) continue;
      BigDecimal monthly = monthly(lease);
      rentByProperty.merge(lease.getProperty().getId(), monthly, BigDecimal::add);
    }

    List<DashboardDtos.PropertyPerformance> rows = new ArrayList<>();
    for (Property property : properties) {
      long[] counts = unitCounts.getOrDefault(property.getId(), new long[2]);
      rows.add(
          new DashboardDtos.PropertyPerformance(
              property.getId(),
              property.getPropertyCode(),
              property.getName(),
              property.getCity(),
              money(property.getCurrentValuation()),
              money(rentByProperty.getOrDefault(property.getId(), MoneyPolicy.zero())),
              counts[0],
              counts[1],
              percent(counts[1], counts[0])));
    }

    List<DashboardDtos.PropertyPerformance> byValuation =
        rows.stream()
            .sorted(Comparator.comparing(DashboardDtos.PropertyPerformance::valuation).reversed())
            .limit(RANKING_SIZE)
            .toList();
    List<DashboardDtos.PropertyPerformance> byRent =
        rows.stream()
            .sorted(Comparator.comparing(DashboardDtos.PropertyPerformance::monthlyRent).reversed())
            .limit(RANKING_SIZE)
            .toList();
    return new Rankings(byValuation, byRent);
  }

  /** A lease's rent normalised to a month, so a quarterly lease is comparable with a monthly one. */
  private static BigDecimal monthly(Lease lease) {
    BigDecimal amount = lease.getRentAmount() == null ? BigDecimal.ZERO : lease.getRentAmount();
    int months = lease.getRentFrequency() == null ? 1 : Math.max(1, lease.getRentFrequency().monthsPerPeriod());
    return amount.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
  }

  // ----------------------------------------------------------------------------------------------
  // The desks
  // ----------------------------------------------------------------------------------------------

  private DashboardDtos.LeadKpi leadKpi() {
    Map<LeadStage, Long> counts = new EnumMap<>(LeadStage.class);
    for (Object[] row : leads.countGroupedByStage()) {
      counts.put((LeadStage) row[0], ((Number) row[1]).longValue());
    }
    List<DashboardDtos.StageCount> funnel = new ArrayList<>();
    long total = 0;
    long open = 0;
    for (LeadStage stage : LeadStage.values()) {
      long count = counts.getOrDefault(stage, 0L);
      funnel.add(new DashboardDtos.StageCount(stage.name(), count));
      total += count;
      if (stage.isOpen()) open += count;
    }
    BigDecimal pipeline = money(leads.sumExpectedValueInOpenStages(CLOSED_LEAD_STAGES));
    return new DashboardDtos.LeadKpi(total, open, pipeline, funnel);
  }

  private DashboardDtos.ListingKpi listingKpi(LocalDate today) {
    List<Listing> live = listings.findAllByStatusNotInAndDeletedAtIsNull(CLOSED_LISTING_STATUSES);
    LocalDate staleBefore = today.minusDays(config.getPortfolio().getListingStaleDays());
    long stale =
        live.stream()
            .filter(listing -> listing.getListingStart() != null && listing.getListingStart().isBefore(staleBefore))
            .count();

    List<DashboardDtos.StatusCount> byStatus = new ArrayList<>();
    for (ListingStatus status : ListingStatus.values()) {
      byStatus.add(
          new DashboardDtos.StatusCount(status.name(), listings.countByStatusAndDeletedAtIsNull(status)));
    }
    return new DashboardDtos.ListingKpi(
        listings.countByDeletedAtIsNull(), live.size(), stale, byStatus);
  }

  private DashboardDtos.ViewingKpi viewingKpi() {
    Map<ViewingStatus, Long> counts = new EnumMap<>(ViewingStatus.class);
    long total = 0;
    for (ViewingStatus status : ViewingStatus.values()) {
      long count = this.viewings.countByStatusAndDeletedAtIsNull(status);
      counts.put(status, count);
      total += count;
    }
    long completed = counts.getOrDefault(ViewingStatus.COMPLETED, 0L);
    long cancelled = counts.getOrDefault(ViewingStatus.CANCELLED, 0L);
    long noShow = counts.getOrDefault(ViewingStatus.NO_SHOW, 0L);
    long decided = completed + cancelled + noShow;
    return new DashboardDtos.ViewingKpi(
        total,
        counts.getOrDefault(ViewingStatus.SCHEDULED, 0L),
        counts.getOrDefault(ViewingStatus.CONFIRMED, 0L),
        completed,
        cancelled,
        noShow,
        percent(completed, decided));
  }

  private DashboardDtos.ApplicationKpi applicationKpi() {
    List<DashboardDtos.StatusCount> byStatus = new ArrayList<>();
    long total = 0;
    for (ApplicationStatus status : ApplicationStatus.values()) {
      long count = applications.countByStatusAndDeletedAtIsNull(status);
      byStatus.add(new DashboardDtos.StatusCount(status.name(), count));
      total += count;
    }
    long open =
        applications.countByStatusAndDeletedAtIsNull(ApplicationStatus.SUBMITTED)
            + applications.countByStatusAndDeletedAtIsNull(ApplicationStatus.SCREENING);
    return new DashboardDtos.ApplicationKpi(total, open, byStatus);
  }

  private DashboardDtos.SalesKpi salesKpi() {
    List<DashboardDtos.StageCount> byStage = new ArrayList<>();
    long total = 0;
    Map<SaleStage, Long> counts = new EnumMap<>(SaleStage.class);
    for (SalesOpportunityRepository.StageCount row : sales.countGroupedByStage()) {
      counts.put(row.getStage(), row.getTotal());
    }
    for (SaleStage stage : SaleStage.values()) {
      long count = counts.getOrDefault(stage, 0L);
      byStage.add(new DashboardDtos.StageCount(stage.name(), count));
      total += count;
    }
    return new DashboardDtos.SalesKpi(total, money(sales.sumOpenPipelineValue()), byStage);
  }

  private DashboardDtos.LeaseAttention leaseAttention(LocalDate today) {
    long running =
        leases.countByStatusAndDeletedAtIsNull(LeaseStatus.ACTIVE)
            + leases.countByStatusAndDeletedAtIsNull(LeaseStatus.EXPIRING);
    long warning =
        leases
            .findExpiringBetween(
                today, today.plusDays(config.getPortfolio().getLeaseExpiryWarningDays()), oneRow())
            .getTotalElements();
    long urgent =
        leases
            .findExpiringBetween(
                today, today.plusDays(config.getPortfolio().getLeaseExpiryUrgentDays()), oneRow())
            .getTotalElements();
    long renewal = leases.countByStatusAndDeletedAtIsNull(LeaseStatus.EXPIRING);
    long expired =
        leases
            .findExpiringBetween(ALL_TIME_FROM, today.minusDays(1), oneRow())
            .getTotalElements();
    return new DashboardDtos.LeaseAttention(running, warning, urgent, renewal, expired);
  }

  private DashboardDtos.MaintenanceKpi maintenanceKpi(
      LocalDate periodStart, LocalDate today, YearMonth trendStart, YearMonth trendEnd) {
    long open = maintenance.countByStatusInAndDeletedAtIsNull(OPEN_MAINTENANCE);
    long urgent =
        maintenance.countByStatusInAndPriorityInAndDeletedAtIsNull(OPEN_MAINTENANCE, URGENT_PRIORITIES);

    BigDecimal costThisPeriod =
        money(
            maintenance.sumActualCostBetween(
                periodStart.atStartOfDay(), today.atTime(LocalTime.MAX)));

    List<DashboardDtos.PriorityCount> byPriority = new ArrayList<>();
    Map<MaintenancePriority, Long> counts = new EnumMap<>(MaintenancePriority.class);
    for (MaintenanceRequestRepository.PriorityCount row : maintenance.countGroupedByPriority()) {
      counts.put(row.getPriority(), row.getTotal());
    }
    for (MaintenancePriority priority : MaintenancePriority.values()) {
      byPriority.add(new DashboardDtos.PriorityCount(priority.name(), counts.getOrDefault(priority, 0L)));
    }

    List<DashboardDtos.MonthlyAmount> costTrend = new ArrayList<>();
    for (YearMonth month = trendStart; !month.isAfter(trendEnd); month = month.plusMonths(1)) {
      costTrend.add(
          new DashboardDtos.MonthlyAmount(
              month.toString(),
              money(
                  maintenance.sumActualCostBetween(
                      month.atDay(1).atStartOfDay(), month.atEndOfMonth().atTime(LocalTime.MAX)))));
    }

    return new DashboardDtos.MaintenanceKpi(open, urgent, costThisPeriod, byPriority, costTrend);
  }

  private List<DashboardDtos.ActivityEntry> recentActivity() {
    Page<AuditLog> page =
        audit.findAllWithActor(
            PageRequest.of(0, ACTIVITY_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
    return page.getContent().stream()
        .map(
            entry ->
                new DashboardDtos.ActivityEntry(
                    entry.getId(),
                    entry.getModule(),
                    entry.getAction(),
                    entry.getSeverity() == null ? null : entry.getSeverity().name(),
                    entry.getDescription(),
                    entry.getActorName(),
                    entry.getEntityType(),
                    entry.getEntityLabel(),
                    entry.getCreatedAt()))
        .toList();
  }

  // ----------------------------------------------------------------------------------------------
  // Arithmetic, guarded
  // ----------------------------------------------------------------------------------------------

  private static Pageable oneRow() {
    return PageRequest.of(0, 1);
  }

  /** A percentage, or zero when there is nothing to measure. Never {@code NaN}. */
  private static int percent(long part, long whole) {
    if (whole <= 0) return 0;
    return (int) Math.round((part * 100.0) / whole);
  }

  /** {@code numerator / denominator x 100} to two places, or zero when the denominator is zero. */
  private static BigDecimal ratioToPercent(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.signum() == 0) return MoneyPolicy.zero();
    return numerator
        .multiply(BigDecimal.valueOf(100))
        .divide(denominator, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal value) {
    return MoneyPolicy.money(value);
  }
}
