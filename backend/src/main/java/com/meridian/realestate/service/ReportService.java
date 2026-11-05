package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.LeadStage;
import com.meridian.realestate.domain.MaintenancePriority;
import com.meridian.realestate.domain.MaintenanceStatus;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.RentInvoice;
import com.meridian.realestate.domain.SalesOpportunity;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.dto.ReportDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.PortfolioPropertyRepository;
import com.meridian.realestate.repository.PortfolioRepository;
import com.meridian.realestate.repository.PropertyExpenseRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.RentInvoiceRepository;
import com.meridian.realestate.repository.SalesOpportunityRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports behind the reports screen.
 *
 * <p><b>Every figure is real.</b> Each report is built by querying the operational tables for the
 * requested window; nothing is seeded, cached or hardcoded, and a figure that cannot be computed
 * honestly from the data is left out rather than guessed. Where a report's rows come from a
 * different aggregate than its totals - for instance the expense split, whose monthly figures and
 * period totals are both read from the ledger's own sum queries - both are stated.</p>
 *
 * <p>All twelve reports share one shape, {@link ReportDtos.ReportTable}, so the grid, the totals row
 * and the CSV export agree by construction rather than by three implementations being kept in step.
 * The report is produced by {@link #run(String, LocalDate, LocalDate, UUID, String)} and then sorted
 * generically by whichever column the caller named, over the real values the rows hold.</p>
 */
@Service
public class ReportService {

  private static final int REPORT_ROW_LIMIT = 500;

  /** The maintenance states that count as outstanding work. */
  private static final List<MaintenanceStatus> OPEN_MAINTENANCE =
      List.of(MaintenanceStatus.OPEN, MaintenanceStatus.ASSIGNED, MaintenanceStatus.IN_PROGRESS);

  /** The lead stages that are no longer pipeline. */
  private static final List<LeadStage> CLOSED_LEAD_STAGES = List.of(LeadStage.WON, LeadStage.LOST);

  private final PropertyRepository properties;
  private final UnitRepository units;
  private final LeaseRepository leases;
  private final RentInvoiceRepository invoices;
  private final PropertyExpenseRepository expenses;
  private final MaintenanceRequestRepository maintenance;
  private final LeadRepository leads;
  private final SalesOpportunityRepository sales;
  private final AgentProfileRepository agents;
  private final PortfolioRepository portfolios;
  private final PortfolioPropertyRepository portfolioProperties;
  private final TenantRepository tenants;
  private final AppProperties config;

  public ReportService(
      PropertyRepository properties,
      UnitRepository units,
      LeaseRepository leases,
      RentInvoiceRepository invoices,
      PropertyExpenseRepository expenses,
      MaintenanceRequestRepository maintenance,
      LeadRepository leads,
      SalesOpportunityRepository sales,
      AgentProfileRepository agents,
      PortfolioRepository portfolios,
      PortfolioPropertyRepository portfolioProperties,
      TenantRepository tenants,
      AppProperties config) {
    this.properties = properties;
    this.units = units;
    this.leases = leases;
    this.invoices = invoices;
    this.expenses = expenses;
    this.maintenance = maintenance;
    this.leads = leads;
    this.sales = sales;
    this.agents = agents;
    this.portfolios = portfolios;
    this.portfolioProperties = portfolioProperties;
    this.tenants = tenants;
    this.config = config;
  }

  /** The report keys this installation serves, so a client offers only real ones. */
  public static final List<String> KEYS =
      List.of(
          "property-performance",
          "occupancy",
          "rental-income",
          "outstanding-rent",
          "lease-expiry",
          "tenant",
          "maintenance-cost",
          "expense",
          "sales",
          "lead-conversion",
          "portfolio-performance",
          "agent-performance");

  /**
   * Produce a report, then sort it by the named column.
   *
   * @param key the report key
   * @param from the inclusive window start; null defaults to the first of the month eleven months ago
   * @param to the inclusive window end; null defaults to today
   * @param propertyId an optional property filter, applied only by the reports that can honour it
   * @param sort an optional column key to sort by
   */
  @Transactional(readOnly = true)
  public ReportDtos.ReportTable run(
      String key, LocalDate from, LocalDate to, UUID propertyId, String sort) {

    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate start = from == null ? end.minusMonths(11).withDayOfMonth(1) : from;
    if (end.isBefore(start)) {
      throw ApiException.badRequest("The report window ends before it begins");
    }

    ReportDtos.ReportTable table =
        switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
          case "property-performance" -> propertyPerformance(start, end, propertyId);
          case "occupancy" -> occupancy(end);
          case "rental-income" -> rentalIncome(start, end);
          case "outstanding-rent" -> outstandingRent(end);
          case "lease-expiry" -> leaseExpiry(start, end);
          case "tenant" -> tenantReport();
          case "maintenance-cost" -> maintenanceCost(start, end);
          case "expense" -> expense(start, end);
          case "sales" -> salesReport();
          case "lead-conversion" -> leadConversion();
          case "portfolio-performance" -> portfolioPerformance();
          case "agent-performance" -> agentPerformance();
          default ->
              throw ApiException.badRequest(
                  "'" + key + "' is not a report this installation serves",
                  KEYS.stream().map(k -> "report: " + k).toList());
        };

    return sort == null || sort.isBlank() ? table : sortRows(table, sort);
  }

  // ----------------------------------------------------------------------------------------------
  // 1. Property performance
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable propertyPerformance(LocalDate from, LocalDate to, UUID propertyId) {
    List<Property> all = properties.findAllByDeletedAtIsNull();
    if (propertyId != null) all = all.stream().filter(p -> p.getId().equals(propertyId)).toList();

    Map<UUID, long[]> occupancy = occupancyByProperty();
    Map<UUID, BigDecimal> rent = monthlyRentByProperty();

    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal totalValuation = MoneyPolicy.zero();
    BigDecimal totalOperating = MoneyPolicy.zero();
    BigDecimal totalCapital = MoneyPolicy.zero();

    for (Property property : all) {
      long[] counts = occupancy.getOrDefault(property.getId(), new long[2]);
      BigDecimal valuation = money(property.getCurrentValuation());
      BigDecimal operating = money(expenses.sumOperatingExpensesForProperty(property.getId(), from, to));
      BigDecimal capital = money(expenses.sumCapitalExpensesForProperty(property.getId(), from, to));
      totalValuation = totalValuation.add(valuation);
      totalOperating = totalOperating.add(operating);
      totalCapital = totalCapital.add(capital);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("propertyCode", property.getPropertyCode());
      row.put("name", property.getName());
      row.put("city", property.getCity());
      row.put("status", property.getStatus() == null ? null : property.getStatus().name());
      row.put("valuation", valuation);
      row.put("acquisitionValue", money(property.getAcquisitionValue()));
      row.put("leasableUnits", counts[0]);
      row.put("occupiedUnits", counts[1]);
      row.put("occupancyPercent", percent(counts[1], counts[0]));
      row.put("monthlyRent", money(rent.getOrDefault(property.getId(), MoneyPolicy.zero())));
      row.put("operatingExpenses", operating);
      row.put("capitalExpenses", capital);
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("valuation", money(totalValuation));
    totals.put("operatingExpenses", money(totalOperating));
    totals.put("capitalExpenses", money(totalCapital));
    totals.put("properties", (long) rows.size());

    return new ReportDtos.ReportTable(
        "property-performance",
        "Property Performance",
        from,
        to,
        LocalDateTime.now(),
        List.of(
            column("propertyCode", "Code", ReportDtos.ColumnType.TEXT),
            column("name", "Property", ReportDtos.ColumnType.TEXT),
            column("city", "City", ReportDtos.ColumnType.TEXT),
            column("status", "Status", ReportDtos.ColumnType.TEXT),
            column("valuation", "Current Valuation", ReportDtos.ColumnType.MONEY),
            column("acquisitionValue", "Acquisition Value", ReportDtos.ColumnType.MONEY),
            column("leasableUnits", "Leasable Units", ReportDtos.ColumnType.INTEGER),
            column("occupiedUnits", "Occupied Units", ReportDtos.ColumnType.INTEGER),
            column("occupancyPercent", "Occupancy %", ReportDtos.ColumnType.INTEGER),
            column("monthlyRent", "Monthly Rent", ReportDtos.ColumnType.MONEY),
            column("operatingExpenses", "Operating Expenses", ReportDtos.ColumnType.MONEY),
            column("capitalExpenses", "Capital Expenses", ReportDtos.ColumnType.MONEY)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 2. Occupancy
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable occupancy(LocalDate asOf) {
    List<Property> all = properties.findAllByDeletedAtIsNull();
    Map<UUID, long[]> byProperty = occupancyByProperty();
    int target = config.getPortfolio().getOccupancyTargetPercent();

    List<Map<String, Object>> rows = new ArrayList<>();
    long totalLeasable = 0;
    long totalOccupied = 0;

    for (Property property : all) {
      long[] counts = byProperty.getOrDefault(property.getId(), new long[2]);
      totalLeasable += counts[0];
      totalOccupied += counts[1];
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("propertyCode", property.getPropertyCode());
      row.put("name", property.getName());
      row.put("city", property.getCity());
      row.put("leasableUnits", counts[0]);
      row.put("occupiedUnits", counts[1]);
      row.put("vacantUnits", Math.max(0, counts[0] - counts[1]));
      row.put("occupancyPercent", percent(counts[1], counts[0]));
      row.put("targetPercent", target);
      row.put("variancePercent", percent(counts[1], counts[0]) - target);
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("leasableUnits", totalLeasable);
    totals.put("occupiedUnits", totalOccupied);
    totals.put("vacantUnits", Math.max(0, totalLeasable - totalOccupied));
    totals.put("occupancyPercent", percent(totalOccupied, totalLeasable));

    return new ReportDtos.ReportTable(
        "occupancy",
        "Occupancy",
        asOf,
        asOf,
        LocalDateTime.now(),
        List.of(
            column("propertyCode", "Code", ReportDtos.ColumnType.TEXT),
            column("name", "Property", ReportDtos.ColumnType.TEXT),
            column("city", "City", ReportDtos.ColumnType.TEXT),
            column("leasableUnits", "Leasable Units", ReportDtos.ColumnType.INTEGER),
            column("occupiedUnits", "Occupied Units", ReportDtos.ColumnType.INTEGER),
            column("vacantUnits", "Vacant Units", ReportDtos.ColumnType.INTEGER),
            column("occupancyPercent", "Occupancy %", ReportDtos.ColumnType.INTEGER),
            column("targetPercent", "Target %", ReportDtos.ColumnType.INTEGER),
            column("variancePercent", "Variance", ReportDtos.ColumnType.INTEGER)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 3. Rental income
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable rentalIncome(LocalDate from, LocalDate to) {
    Map<YearMonth, RentInvoiceRepository.MonthlyInvoicedVersusCollected> byMonth = new LinkedHashMap<>();
    for (RentInvoiceRepository.MonthlyInvoicedVersusCollected row :
        invoices.monthlyInvoicedVersusCollected(from, to)) {
      byMonth.put(YearMonth.of(row.getPeriodYear(), row.getPeriodMonth()), row);
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal totalInvoiced = MoneyPolicy.zero();
    BigDecimal totalCollected = MoneyPolicy.zero();

    for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
      RentInvoiceRepository.MonthlyInvoicedVersusCollected row = byMonth.get(month);
      BigDecimal invoiced = money(row == null ? null : row.getInvoiced());
      BigDecimal collected = money(row == null ? null : row.getCollected());
      totalInvoiced = totalInvoiced.add(invoiced);
      totalCollected = totalCollected.add(collected);

      Map<String, Object> tableRow = new LinkedHashMap<>();
      tableRow.put("month", month.toString());
      tableRow.put("invoiced", invoiced);
      tableRow.put("collected", collected);
      tableRow.put("difference", money(invoiced.subtract(collected)));
      tableRow.put("collectionRatePercent", rate(collected, invoiced));
      rows.add(tableRow);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("invoiced", money(totalInvoiced));
    totals.put("collected", money(totalCollected));
    totals.put("difference", money(totalInvoiced.subtract(totalCollected)));
    totals.put("collectionRatePercent", rate(totalCollected, totalInvoiced));

    return new ReportDtos.ReportTable(
        "rental-income",
        "Rental Income",
        from,
        to,
        LocalDateTime.now(),
        List.of(
            column("month", "Month", ReportDtos.ColumnType.TEXT),
            column("invoiced", "Invoiced", ReportDtos.ColumnType.MONEY),
            column("collected", "Collected", ReportDtos.ColumnType.MONEY),
            column("difference", "Difference", ReportDtos.ColumnType.MONEY),
            column("collectionRatePercent", "Collection %", ReportDtos.ColumnType.DECIMAL)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 4. Outstanding rent
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable outstandingRent(LocalDate asOf) {
    var page = invoices.findPastDueCollectable(asOf, PageRequest.of(0, REPORT_ROW_LIMIT, Sort.by("dueDate")));

    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal totalOutstanding = MoneyPolicy.zero();

    for (RentInvoice invoice : page.getContent()) {
      BigDecimal outstanding =
          invoices
              .findOutstandingByInvoiceId(invoice.getId())
              .map(RentInvoiceRepository.InvoiceOutstanding::getOutstanding)
              .map(MoneyPolicy::money)
              .orElseGet(() -> money(invoice.getTotalAmount().subtract(invoice.getCreditedAmount())));
      totalOutstanding = totalOutstanding.add(outstanding);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("invoiceNumber", invoice.getInvoiceNumber());
      row.put("dueDate", invoice.getDueDate());
      row.put("daysOverdue", invoice.getDueDate() == null ? null : ChronoUnit.DAYS.between(invoice.getDueDate(), asOf));
      row.put("totalAmount", money(invoice.getTotalAmount()));
      row.put("creditedAmount", money(invoice.getCreditedAmount()));
      row.put("outstanding", outstanding);
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("outstanding", money(totalOutstanding));
    totals.put("invoices", page.getTotalElements());

    return new ReportDtos.ReportTable(
        "outstanding-rent",
        "Outstanding Rent",
        null,
        asOf,
        LocalDateTime.now(),
        List.of(
            column("invoiceNumber", "Invoice", ReportDtos.ColumnType.TEXT),
            column("dueDate", "Due", ReportDtos.ColumnType.DATE),
            column("daysOverdue", "Days Overdue", ReportDtos.ColumnType.INTEGER),
            column("totalAmount", "Invoiced", ReportDtos.ColumnType.MONEY),
            column("creditedAmount", "Credited", ReportDtos.ColumnType.MONEY),
            column("outstanding", "Outstanding", ReportDtos.ColumnType.MONEY)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 5. Lease expiry
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable leaseExpiry(LocalDate from, LocalDate to) {
    var page = leases.findExpiringBetween(from, to, PageRequest.of(0, REPORT_ROW_LIMIT, Sort.by("endDate")));

    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal totalRent = MoneyPolicy.zero();
    for (Lease lease : page.getContent()) {
      BigDecimal rent = monthly(lease);
      totalRent = totalRent.add(rent);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("leaseCode", lease.getLeaseCode());
      row.put("propertyCode", lease.getProperty() == null ? null : lease.getProperty().getPropertyCode());
      row.put("status", lease.getStatus() == null ? null : lease.getStatus().name());
      row.put("startDate", lease.getStartDate());
      row.put("endDate", lease.getEndDate());
      row.put("daysToExpiry", lease.getEndDate() == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), lease.getEndDate()));
      row.put("monthlyRent", rent);
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("leases", page.getTotalElements());
    totals.put("monthlyRent", money(totalRent));

    return new ReportDtos.ReportTable(
        "lease-expiry",
        "Lease Expiry",
        from,
        to,
        LocalDateTime.now(),
        List.of(
            column("leaseCode", "Lease", ReportDtos.ColumnType.TEXT),
            column("propertyCode", "Property", ReportDtos.ColumnType.TEXT),
            column("status", "Status", ReportDtos.ColumnType.TEXT),
            column("startDate", "Start", ReportDtos.ColumnType.DATE),
            column("endDate", "End", ReportDtos.ColumnType.DATE),
            column("daysToExpiry", "Days To Expiry", ReportDtos.ColumnType.INTEGER),
            column("monthlyRent", "Monthly Rent", ReportDtos.ColumnType.MONEY)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 6. Tenants
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable tenantReport() {
    List<Tenant> all = tenants.findAllByDeletedAtIsNull();
    Map<UUID, BigDecimal> rentByTenant = new HashMap<>();
    Map<UUID, Long> leasesByTenant = new HashMap<>();
    for (Lease lease : leases.findAllByDeletedAtIsNull()) {
      if (lease.getStatus() == null || !lease.getStatus().isRunning()) continue;
      rentByTenant.merge(lease.getTenantId(), monthly(lease), BigDecimal::add);
      leasesByTenant.merge(lease.getTenantId(), 1L, Long::sum);
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal totalRent = MoneyPolicy.zero();
    for (Tenant tenant : all) {
      BigDecimal rent = money(rentByTenant.getOrDefault(tenant.getId(), MoneyPolicy.zero()));
      totalRent = totalRent.add(rent);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tenantCode", tenant.getTenantCode());
      row.put("displayName", tenant.getDisplayName());
      row.put("status", tenant.getStatus() == null ? null : tenant.getStatus().name());
      row.put("email", tenant.getEmail());
      row.put("city", tenant.getCity());
      row.put("activeLeases", leasesByTenant.getOrDefault(tenant.getId(), 0L));
      row.put("monthlyRent", rent);
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("tenants", (long) rows.size());
    totals.put("monthlyRent", money(totalRent));

    return new ReportDtos.ReportTable(
        "tenant",
        "Tenants",
        null,
        LocalDate.now(),
        LocalDateTime.now(),
        List.of(
            column("tenantCode", "Code", ReportDtos.ColumnType.TEXT),
            column("displayName", "Tenant", ReportDtos.ColumnType.TEXT),
            column("status", "Status", ReportDtos.ColumnType.TEXT),
            column("email", "Email", ReportDtos.ColumnType.TEXT),
            column("city", "City", ReportDtos.ColumnType.TEXT),
            column("activeLeases", "Active Leases", ReportDtos.ColumnType.INTEGER),
            column("monthlyRent", "Monthly Rent", ReportDtos.ColumnType.MONEY)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 7. Maintenance cost
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable maintenanceCost(LocalDate from, LocalDate to) {
    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal total = MoneyPolicy.zero();
    for (YearMonth month = YearMonth.from(from);
        !month.isAfter(YearMonth.from(to));
        month = month.plusMonths(1)) {
      BigDecimal monthTotal =
          money(
              maintenance.sumActualCostBetween(
                  month.atDay(1).atStartOfDay(), month.atEndOfMonth().atTime(LocalTime.MAX)));
      total = total.add(monthTotal);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("month", month.toString());
      row.put("cost", monthTotal);
      rows.add(row);
    }

    long open = maintenance.countByStatusInAndDeletedAtIsNull(OPEN_MAINTENANCE);
    long urgent =
        maintenance.countByStatusInAndPriorityInAndDeletedAtIsNull(
            OPEN_MAINTENANCE, List.of(MaintenancePriority.HIGH, MaintenancePriority.URGENT));

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("cost", money(total));
    totals.put("openRequests", open);
    totals.put("urgentOpenRequests", urgent);

    return new ReportDtos.ReportTable(
        "maintenance-cost",
        "Maintenance Cost",
        from,
        to,
        LocalDateTime.now(),
        List.of(
            column("month", "Month", ReportDtos.ColumnType.TEXT),
            column("cost", "Actual Cost", ReportDtos.ColumnType.MONEY)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 8. Expense
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable expense(LocalDate from, LocalDate to) {
    Map<YearMonth, BigDecimal> recoveredByMonth = new HashMap<>();
    for (PropertyExpenseRepository.MonthlyExpenseTrend trend : expenses.monthlyExpenseTrend(from, to)) {
      recoveredByMonth.put(
          YearMonth.of(trend.getPeriodYear(), trend.getPeriodMonth()), money(trend.getRecovered()));
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal totalOperating = MoneyPolicy.zero();
    BigDecimal totalCapital = MoneyPolicy.zero();
    BigDecimal totalRecovered = MoneyPolicy.zero();

    for (YearMonth month = YearMonth.from(from);
        !month.isAfter(YearMonth.from(to));
        month = month.plusMonths(1)) {
      BigDecimal operating = money(expenses.sumOperatingExpenses(month.atDay(1), month.atEndOfMonth()));
      BigDecimal capital = money(expenses.sumCapitalExpenses(month.atDay(1), month.atEndOfMonth()));
      BigDecimal recovered = recoveredByMonth.getOrDefault(month, MoneyPolicy.zero());
      totalOperating = totalOperating.add(operating);
      totalCapital = totalCapital.add(capital);
      totalRecovered = totalRecovered.add(recovered);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("month", month.toString());
      row.put("operating", operating);
      row.put("capital", capital);
      row.put("recovered", recovered);
      row.put("total", money(operating.add(capital)));
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("operating", money(totalOperating));
    totals.put("capital", money(totalCapital));
    totals.put("recovered", money(totalRecovered));
    totals.put("total", money(totalOperating.add(totalCapital)));

    return new ReportDtos.ReportTable(
        "expense",
        "Expenses",
        from,
        to,
        LocalDateTime.now(),
        List.of(
            column("month", "Month", ReportDtos.ColumnType.TEXT),
            column("operating", "Operating", ReportDtos.ColumnType.MONEY),
            column("capital", "Capital", ReportDtos.ColumnType.MONEY),
            column("recovered", "Recovered", ReportDtos.ColumnType.MONEY),
            column("total", "Total", ReportDtos.ColumnType.MONEY)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 9. Sales
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable salesReport() {
    List<SalesOpportunity> open =
        sales.findAllForPipeline().stream()
            .filter(sale -> sale.getStage() != null && !sale.getStage().isConcluded())
            .toList();

    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal totalExpected = MoneyPolicy.zero();
    for (SalesOpportunity sale : open) {
      BigDecimal expected = money(sale.getExpectedPrice());
      totalExpected = totalExpected.add(expected);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("saleCode", sale.getSaleCode());
      row.put("propertyCode", sale.getProperty() == null ? null : sale.getProperty().getPropertyCode());
      row.put("stage", sale.getStage().name());
      row.put("buyerName", sale.getBuyer() == null ? null : sale.getBuyer().getDisplayName());
      row.put("askingPrice", money(sale.getAskingPrice()));
      row.put("expectedPrice", expected);
      row.put("expectedCloseDate", sale.getExpectedCloseDate());
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("openDeals", (long) rows.size());
    totals.put("expectedPrice", money(totalExpected));
    totals.put("openPipelineValue", money(sales.sumOpenPipelineValue()));

    return new ReportDtos.ReportTable(
        "sales",
        "Sales Pipeline",
        null,
        LocalDate.now(),
        LocalDateTime.now(),
        List.of(
            column("saleCode", "Sale", ReportDtos.ColumnType.TEXT),
            column("propertyCode", "Property", ReportDtos.ColumnType.TEXT),
            column("stage", "Stage", ReportDtos.ColumnType.TEXT),
            column("buyerName", "Buyer", ReportDtos.ColumnType.TEXT),
            column("askingPrice", "Asking", ReportDtos.ColumnType.MONEY),
            column("expectedPrice", "Expected", ReportDtos.ColumnType.MONEY),
            column("expectedCloseDate", "Expected Close", ReportDtos.ColumnType.DATE)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 10. Lead conversion
  // ----------------------------------------------------------------------------------------------

  /**
   * The lead funnel.
   *
   * <p>The figures are the <b>current</b> position of the pipeline, not a windowed one: a lead's
   * stage is its present state rather than a dated event, so "leads that reached NEGOTIATION in
   * March" is not a question the stage column can answer, and inventing an answer would be worse
   * than saying so. The window is echoed on the report but does not scope the funnel.</p>
   */
  private ReportDtos.ReportTable leadConversion() {
    Map<LeadStage, Long> counts = new LinkedHashMap<>();
    for (LeadStage stage : LeadStage.values()) counts.put(stage, 0L);
    for (Object[] row : leads.countGroupedByStage()) {
      counts.put((LeadStage) row[0], ((Number) row[1]).longValue());
    }

    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (LeadStage stage : LeadStage.values()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("stage", stage.name());
      row.put("count", counts.get(stage));
      row.put("sharePercent", rate(BigDecimal.valueOf(counts.get(stage)), BigDecimal.valueOf(total)));
      rows.add(row);
    }

    long won = counts.getOrDefault(LeadStage.WON, 0L);
    long lost = counts.getOrDefault(LeadStage.LOST, 0L);
    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("leads", total);
    totals.put("won", won);
    totals.put("lost", lost);
    totals.put("conversionPercent", rate(BigDecimal.valueOf(won), BigDecimal.valueOf(won + lost)));
    totals.put("openPipelineValue", money(leads.sumExpectedValueInOpenStages(CLOSED_LEAD_STAGES)));

    return new ReportDtos.ReportTable(
        "lead-conversion",
        "Lead Conversion",
        null,
        LocalDate.now(),
        LocalDateTime.now(),
        List.of(
            column("stage", "Stage", ReportDtos.ColumnType.TEXT),
            column("count", "Leads", ReportDtos.ColumnType.INTEGER),
            column("sharePercent", "Share %", ReportDtos.ColumnType.DECIMAL)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 11. Portfolio performance
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable portfolioPerformance() {
    Map<UUID, Long> members = new HashMap<>();
    for (Object[] row : portfolioProperties.countOpenAllocationsByPortfolio()) {
      members.put((UUID) row[0], ((Number) row[1]).longValue());
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    long totalMembers = 0;
    for (var portfolio : portfolios.findAllWithOwnerAndManager()) {
      long count = members.getOrDefault(portfolio.getId(), 0L);
      totalMembers += count;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("portfolioCode", portfolio.getPortfolioCode());
      row.put("name", portfolio.getName());
      row.put("strategy", portfolio.getStrategy() == null ? null : portfolio.getStrategy().name());
      row.put("status", portfolio.getStatus() == null ? null : portfolio.getStatus().name());
      row.put("ownerName", portfolio.getOwner() == null ? null : portfolio.getOwner().getDisplayName());
      row.put(
          "managerName",
          portfolio.getManager() == null ? null : portfolio.getManager().getFullName());
      row.put("targetYieldPercent", portfolio.getTargetYieldPercent());
      row.put("memberProperties", count);
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("portfolios", (long) rows.size());
    totals.put("memberProperties", totalMembers);

    return new ReportDtos.ReportTable(
        "portfolio-performance",
        "Portfolio Performance",
        null,
        LocalDate.now(),
        LocalDateTime.now(),
        List.of(
            column("portfolioCode", "Code", ReportDtos.ColumnType.TEXT),
            column("name", "Portfolio", ReportDtos.ColumnType.TEXT),
            column("strategy", "Strategy", ReportDtos.ColumnType.TEXT),
            column("status", "Status", ReportDtos.ColumnType.TEXT),
            column("ownerName", "Owner", ReportDtos.ColumnType.TEXT),
            column("managerName", "Manager", ReportDtos.ColumnType.TEXT),
            column("targetYieldPercent", "Target Yield %", ReportDtos.ColumnType.DECIMAL),
            column("memberProperties", "Properties", ReportDtos.ColumnType.INTEGER)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // 12. Agent performance
  // ----------------------------------------------------------------------------------------------

  private ReportDtos.ReportTable agentPerformance() {
    List<Map<String, Object>> rows = new ArrayList<>();
    long totalLeads = 0;
    long totalSales = 0;

    for (var agent : agents.findAllWithUserAndOffice()) {
      List<com.meridian.realestate.domain.Lead> held =
          leads.findAllByOwnerAgentIdAndDeletedAtIsNull(agent.getId());
      long openLeads = held.stream().filter(lead -> lead.getStage() != null && lead.getStage().isOpen()).count();
      long won = held.stream().filter(lead -> lead.getStage() == LeadStage.WON).count();
      long salesHeld = sales.findAllByOwnerAgentIdAndDeletedAtIsNull(agent.getId()).size();

      totalLeads += held.size();
      totalSales += salesHeld;

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("agentCode", agent.getAgentCode());
      row.put("name", agent.getUser() == null ? null : agent.getUser().getFullName());
      row.put("office", agent.getOffice() == null ? null : agent.getOffice().getName());
      row.put("specialisation", agent.getSpecialisation() == null ? null : agent.getSpecialisation().name());
      row.put("leadsHeld", held.size());
      row.put("openLeads", openLeads);
      row.put("wonLeads", won);
      row.put("salesHeld", salesHeld);
      row.put("conversionPercent", rate(BigDecimal.valueOf(won), BigDecimal.valueOf(held.size())));
      rows.add(row);
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("agents", (long) rows.size());
    totals.put("leadsHeld", totalLeads);
    totals.put("salesHeld", totalSales);

    return new ReportDtos.ReportTable(
        "agent-performance",
        "Agent Performance",
        null,
        LocalDate.now(),
        LocalDateTime.now(),
        List.of(
            column("agentCode", "Code", ReportDtos.ColumnType.TEXT),
            column("name", "Agent", ReportDtos.ColumnType.TEXT),
            column("office", "Office", ReportDtos.ColumnType.TEXT),
            column("specialisation", "Specialisation", ReportDtos.ColumnType.TEXT),
            column("leadsHeld", "Leads Held", ReportDtos.ColumnType.INTEGER),
            column("openLeads", "Open Leads", ReportDtos.ColumnType.INTEGER),
            column("wonLeads", "Won", ReportDtos.ColumnType.INTEGER),
            column("salesHeld", "Sales", ReportDtos.ColumnType.INTEGER),
            column("conversionPercent", "Conversion %", ReportDtos.ColumnType.DECIMAL)),
        rows,
        totals);
  }

  // ----------------------------------------------------------------------------------------------
  // Shared helpers
  // ----------------------------------------------------------------------------------------------

  private Map<UUID, long[]> occupancyByProperty() {
    Map<UUID, long[]> byProperty = new HashMap<>();
    for (Unit unit : units.findAllByDeletedAtIsNull()) {
      if (!unit.isLeasable()) continue;
      long[] counts = byProperty.computeIfAbsent(unit.getProperty().getId(), key -> new long[2]);
      counts[0]++;
      if (unit.getStatus() == UnitStatus.OCCUPIED) counts[1]++;
    }
    return byProperty;
  }

  private Map<UUID, BigDecimal> monthlyRentByProperty() {
    Map<UUID, BigDecimal> byProperty = new HashMap<>();
    for (Lease lease : leases.findAllByDeletedAtIsNull()) {
      if (lease.getStatus() == null || !lease.getStatus().isRunning()) continue;
      byProperty.merge(lease.getProperty().getId(), monthly(lease), BigDecimal::add);
    }
    return byProperty;
  }

  private static BigDecimal monthly(Lease lease) {
    BigDecimal amount = lease.getRentAmount() == null ? BigDecimal.ZERO : lease.getRentAmount();
    int months = lease.getRentFrequency() == null ? 1 : Math.max(1, lease.getRentFrequency().monthsPerPeriod());
    return amount.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
  }

  private static ReportDtos.ReportColumn column(
      String key, String label, ReportDtos.ColumnType type) {
    return new ReportDtos.ReportColumn(key, label, type);
  }

  private static int percent(long part, long whole) {
    if (whole <= 0) return 0;
    return (int) Math.round((part * 100.0) / whole);
  }

  private static BigDecimal rate(BigDecimal part, BigDecimal whole) {
    if (whole == null || whole.signum() == 0) return MoneyPolicy.zero();
    return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal value) {
    return MoneyPolicy.money(value);
  }

  /** Sort a finished report by one of its own columns, over the real values the rows hold. */
  private static ReportDtos.ReportTable sortRows(ReportDtos.ReportTable table, String sortField) {
    boolean descending = sortField.startsWith("-");
    String field = descending ? sortField.substring(1) : sortField;

    boolean known = table.columns().stream().anyMatch(column -> column.key().equals(field));
    if (!known) {
      throw ApiException.badRequest(
          "The value '" + field + "' is not a column this report can be sorted by",
          table.columns().stream().map(column -> "sort: " + column.key()).toList());
    }

    List<Map<String, Object>> sorted = new ArrayList<>(table.rows());
    Comparator<Map<String, Object>> comparator =
        Comparator.comparing(row -> row.get(field), ReportService::compareValues);
    if (descending) comparator = comparator.reversed();
    sorted.sort(comparator);

    return new ReportDtos.ReportTable(
        table.key(),
        table.title(),
        table.from(),
        table.to(),
        table.generatedAt(),
        table.columns(),
        sorted,
        table.totals());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compareValues(Object left, Object right) {
    if (left == null && right == null) return 0;
    if (left == null) return -1;
    if (right == null) return 1;
    if (left instanceof Number a && right instanceof Number b) {
      return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
    }
    if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
      return comparable.compareTo(right);
    }
    return String.valueOf(left).compareTo(String.valueOf(right));
  }
}
