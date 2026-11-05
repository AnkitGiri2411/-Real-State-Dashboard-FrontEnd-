package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.Owner;
import com.meridian.realestate.domain.Portfolio;
import com.meridian.realestate.domain.PortfolioProperty;
import com.meridian.realestate.domain.PortfolioStatus;
import com.meridian.realestate.domain.PortfolioStrategy;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.PortfolioDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PortfolioPropertyRepository;
import com.meridian.realestate.repository.PortfolioRepository;
import com.meridian.realestate.repository.PropertyExpenseRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The books of assets: what each one holds, and what it earns.
 *
 * <p><b>Why this class exists.</b> A portfolio is what makes "portfolio value", "portfolio yield" and
 * "portfolio occupancy" meaningful questions rather than aggregates over everything the firm has ever
 * touched. The operations that create, change, close and populate a book - and the single place that
 * computes what a book is worth and what it returns - belong together, because a performance figure
 * and the allocation that produced it must not be able to disagree.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>A property is in at most one book.</b> The schema enforces it with a partial unique index,
 *       and this service mirrors the rule with a named refusal: a property that already has an open
 *       allocation is refused as a 409 {@code PROPERTY_ALREADY_IN_PORTFOLIO} rather than allowed to
 *       double-count in every portfolio total silently. The refusal is checked <em>before</em> any
 *       write, so the refused path leaves nothing behind.</li>
 *   <li><b>Allocation is a period, not a flag.</b> A property enters a book at an {@code allocatedAt}
 *       and leaves it at a {@code deallocatedAt}; a disposed asset is history rather than a member, so
 *       the book's composition at a past date stays reconstructable. Deallocating records the date
 *       rather than deleting the row, and a period that would run backwards is refused.</li>
 *   <li><b>A closed book carries its reason.</b> The schema requires a closing note on a closed book,
 *       so a closed book cannot silently re-enter the firm's live totals. The service surfaces that as
 *       a 400 rather than letting the database reject it opaquely.</li>
 * </ul>
 *
 * <p><b>The performance formula, and where each figure comes from.</b> Every number on {@link
 * PortfolioDtos.PortfolioPerformance} is read from a real query over the book's currently-allocated
 * properties - nothing is cached or seeded:</p>
 * <ul>
 *   <li><b>property count</b> - the open allocations of the book;</li>
 *   <li><b>total valuation</b> and <b>total acquisition value</b> - the sum of each member property's
 *       stored {@code current_valuation} and {@code acquisition_value};</li>
 *   <li><b>monthly rent roll</b> - the sum of each member property's running leases' rent, normalised
 *       to a calendar month, which is the income the book produces;</li>
 *   <li><b>operating expenses</b> and <b>capital expenses</b> - the sum over the members of the
 *       expense ledger's own operating and capital sums for the current month, split by
 *       {@code expense_categories.is_capital};</li>
 *   <li><b>net operating income</b> - the monthly rent roll less the operating expenses, with capital
 *       expenditure excluded and reported on its own line. This is the rule the {@code V15} header
 *       states: net income is collected rent less operating expenses, capital is excluded deliberately
 *       rather than netted off, and deposits are neither a cost nor a revenue;</li>
 *   <li><b>occupancy %</b> - occupied leasable units over leasable units across the members, measured
 *       against {@code app.portfolio.occupancy-target-percent};</li>
 *   <li><b>achieved yield %</b> - the annualised rent roll over the current valuation. It is a
 *       <em>derived, achieved</em> figure, and it sits beside - never in place of - the book's
 *       {@code target_yield_percent}, which is a target the book is managed against.</li>
 * </ul>
 *
 * <p><b>Every ratio is guarded.</b> A book with a zero (or absent) valuation reads {@code 0.00}
 * achieved yield rather than {@code NaN} or an exception - a development book holding land is a real
 * state - and a book with no leasable units reads {@code 0%} occupancy. Neither ever divides by zero.</p>
 *
 * <p><b>A note on numbering.</b> {@code V2} seeds no portfolio series, so a book's code is minted from
 * the record's own identity rather than from a counter. It is reported rather than added, because the
 * migration chain is not this task's to change.</p>
 *
 * <p><b>A note on paging.</b> The register's filters are applied here over the fetched books because
 * no combined repository query exists, so the page the caller asked for is the page they get, with a
 * correct total.</p>
 */
@Service
public class PortfolioService {

  /** The basis stated beside a derived yield, so an achieved figure is never mistaken for a target. */
  private static final String YIELD_BASIS = "Annualised rent roll over current valuation";

  private final PortfolioRepository portfolios;
  private final PortfolioPropertyRepository allocations;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final LeaseRepository leases;
  private final PropertyExpenseRepository expenses;
  private final OwnerRepository owners;
  private final UserRepository users;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;
  private final AppProperties config;

  public PortfolioService(
      PortfolioRepository portfolios,
      PortfolioPropertyRepository allocations,
      PropertyRepository properties,
      UnitRepository units,
      LeaseRepository leases,
      PropertyExpenseRepository expenses,
      OwnerRepository owners,
      UserRepository users,
      AuditRecordingService audit,
      AccessScope accessScope,
      AppProperties config) {
    this.portfolios = portfolios;
    this.allocations = allocations;
    this.properties = properties;
    this.units = units;
    this.leases = leases;
    this.expenses = expenses;
    this.owners = owners;
    this.users = users;
    this.audit = audit;
    this.accessScope = accessScope;
    this.config = config;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live book behind an id, or a 404. An archived book is not addressable. */
  @Transactional(readOnly = true)
  public Portfolio require(UUID portfolioId) {
    return portfolios
        .findByIdAndDeletedAtIsNull(portfolioId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PORTFOLIO_NOT_FOUND")));
  }

  /** The register, filtered by status, strategy, owner and manager, with free-text search. */
  @Transactional(readOnly = true)
  public Page<PortfolioDtos.PortfolioSummary> list(
      String status, String strategy, UUID ownerId, UUID managerId, String search, Pageable pageable) {

    PortfolioStatus statusFilter = parseStatus(status, null);
    PortfolioStrategy strategyFilter = parseStrategy(strategy, null);
    String term = blankToNull(search);
    Map<UUID, Long> counts = openAllocationCounts();

    List<PortfolioDtos.PortfolioSummary> matches =
        sortPortfolios(portfolios.findAllWithOwnerAndManager(), pageable.getSort()).stream()
            .filter(portfolio -> statusFilter == null || portfolio.getStatus() == statusFilter)
            .filter(portfolio -> strategyFilter == null || portfolio.getStrategy() == strategyFilter)
            .filter(
                portfolio ->
                    ownerId == null
                        || (portfolio.getOwner() != null && ownerId.equals(portfolio.getOwner().getId())))
            .filter(
                portfolio ->
                    managerId == null
                        || (portfolio.getManager() != null
                            && managerId.equals(portfolio.getManager().getId())))
            .filter(portfolio -> term == null || matchesSearch(portfolio, term))
            .map(portfolio -> PortfolioDtos.PortfolioSummary.from(portfolio, counts.getOrDefault(portfolio.getId(), 0L)))
            .toList();

    return pageOf(matches, pageable);
  }

  /** One book's full record, with its currently-allocated assets. */
  @Transactional(readOnly = true)
  public PortfolioDtos.PortfolioDetail detail(UUID portfolioId) {
    return detailOf(require(portfolioId));
  }

  /**
   * One book's performance. See the class note: every figure is read from a real query, every ratio
   * is guarded, and the target yield is never presented as an achieved one.
   */
  @Transactional(readOnly = true)
  public PortfolioDtos.PortfolioPerformance performance(UUID portfolioId) {
    Portfolio portfolio = require(portfolioId);
    List<PortfolioProperty> members =
        allocations.findAllByPortfolioIdAndDeallocatedAtIsNullAndDeletedAtIsNull(portfolioId);

    YearMonth month = YearMonth.now();
    LocalDate monthStart = month.atDay(1);
    LocalDate monthEnd = month.atEndOfMonth();

    BigDecimal valuation = MoneyPolicy.zero();
    BigDecimal acquisition = MoneyPolicy.zero();
    BigDecimal rentRoll = MoneyPolicy.zero();
    BigDecimal operating = MoneyPolicy.zero();
    BigDecimal capital = MoneyPolicy.zero();
    long leasable = 0;
    long occupied = 0;
    long memberCount = 0;

    for (PortfolioProperty member : members) {
      Property property = member.getProperty();
      if (property == null || property.getId() == null) {
        continue;
      }
      memberCount++;
      UUID propertyId = property.getId();
      valuation = valuation.add(MoneyPolicy.money(property.getCurrentValuation()));
      acquisition = acquisition.add(MoneyPolicy.money(property.getAcquisitionValue()));
      leasable += units.countByPropertyIdAndLeasableTrueAndDeletedAtIsNull(propertyId);
      occupied +=
          units.countByPropertyIdAndLeasableTrueAndStatusAndDeletedAtIsNull(
              propertyId, UnitStatus.OCCUPIED);
      for (Lease lease : leases.findAllByProperty_IdAndDeletedAtIsNull(propertyId, Pageable.unpaged()).getContent()) {
        if (lease.getStatus() != null && lease.getStatus().isRunning()) {
          rentRoll = rentRoll.add(monthly(lease));
        }
      }
      operating =
          operating.add(MoneyPolicy.money(expenses.sumOperatingExpensesForProperty(propertyId, monthStart, monthEnd)));
      capital =
          capital.add(MoneyPolicy.money(expenses.sumCapitalExpensesForProperty(propertyId, monthStart, monthEnd)));
    }

    operating = MoneyPolicy.money(operating);
    capital = MoneyPolicy.money(capital);
    rentRoll = MoneyPolicy.money(rentRoll);
    // Capital is excluded from the running result and reported on its own line. See the class note.
    BigDecimal netOperatingIncome = MoneyPolicy.money(rentRoll.subtract(operating));
    BigDecimal annualisedRent = MoneyPolicy.money(rentRoll.multiply(BigDecimal.valueOf(12)));

    return new PortfolioDtos.PortfolioPerformance(
        portfolio.getId(),
        portfolio.getPortfolioCode(),
        portfolio.getName(),
        portfolio.getStrategy() == null ? null : portfolio.getStrategy().name(),
        portfolio.getStatus() == null ? null : portfolio.getStatus().name(),
        month.atDay(1),
        memberCount,
        MoneyPolicy.money(valuation),
        MoneyPolicy.money(acquisition),
        rentRoll,
        operating,
        capital,
        netOperatingIncome,
        annualisedRent,
        achievedYieldPercent(annualisedRent, valuation),
        portfolio.getTargetYieldPercent(),
        occupancyPercent(occupied, leasable),
        config.getPortfolio().getOccupancyTargetPercent(),
        YIELD_BASIS);
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Create a book. Its code is minted; a closed book must carry its reason. */
  @Transactional
  public PortfolioDtos.PortfolioDetail create(PortfolioDtos.CreatePortfolioRequest request) {
    User actor = accessScope.requireUser();

    PortfolioStatus status = parseStatus(request.status(), PortfolioStatus.ACTIVE);
    String notes = request.notes();
    assertClosedCarriesReason(status, notes);

    Portfolio portfolio = new Portfolio();
    portfolio.setPortfolioCode(mintCode());
    portfolio.setName(request.name().trim());
    portfolio.setDescription(request.description());
    portfolio.setOwner(request.ownerId() == null ? null : requireOwner(request.ownerId()));
    portfolio.setManager(request.managerId() == null ? null : requireManager(request.managerId()));
    portfolio.setStrategy(parseStrategy(request.strategy(), PortfolioStrategy.CORE));
    portfolio.setTargetYieldPercent(request.targetYieldPercent());
    portfolio.setInceptionDate(request.inceptionDate());
    portfolio.setStatus(status);
    portfolio.setNotes(notes);
    portfolio.setCreatedBy(actor.getId());
    portfolio.setUpdatedBy(actor.getId());

    Portfolio saved = portfolios.save(portfolio);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PORTFOLIO,
        AuditActions.CREATE,
        "Created portfolio " + saved.getPortfolioCode() + " (" + saved.getName() + ")",
        "PORTFOLIO",
        saved.getId(),
        saved.getPortfolioCode());

    return detailOf(saved);
  }

  /** Change a book. Only the fields supplied are applied. */
  @Transactional
  public PortfolioDtos.PortfolioDetail update(UUID portfolioId, PortfolioDtos.UpdatePortfolioRequest request) {
    User actor = accessScope.requireUser();
    Portfolio portfolio = require(portfolioId);

    if (request.name() != null && !request.name().isBlank()) portfolio.setName(request.name().trim());
    if (request.description() != null) portfolio.setDescription(request.description());
    if (request.ownerId() != null) portfolio.setOwner(requireOwner(request.ownerId()));
    if (request.managerId() != null) portfolio.setManager(requireManager(request.managerId()));
    if (request.strategy() != null && !request.strategy().isBlank()) {
      portfolio.setStrategy(parseStrategy(request.strategy(), portfolio.getStrategy()));
    }
    if (request.targetYieldPercent() != null) {
      portfolio.setTargetYieldPercent(request.targetYieldPercent());
    }
    if (request.inceptionDate() != null) portfolio.setInceptionDate(request.inceptionDate());
    PortfolioStatus status = request.status() == null || request.status().isBlank()
        ? portfolio.getStatus()
        : parseStatus(request.status(), portfolio.getStatus());
    String notes = request.notes() == null ? portfolio.getNotes() : request.notes();
    assertClosedCarriesReason(status, notes);
    portfolio.setStatus(status);
    portfolio.setNotes(notes);
    portfolio.setUpdatedBy(actor.getId());

    Portfolio saved = portfolios.save(portfolio);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PORTFOLIO,
        AuditActions.UPDATE,
        "Changed portfolio " + saved.getPortfolioCode(),
        "PORTFOLIO",
        saved.getId(),
        saved.getPortfolioCode());

    return detailOf(saved);
  }

  /** Archive a book - a soft delete, once. */
  @Transactional
  public PortfolioDtos.PortfolioDetail archive(UUID portfolioId) {
    User actor = accessScope.requireUser();

    Portfolio portfolio =
        portfolios
            .findById(portfolioId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PORTFOLIO_NOT_FOUND")));
    if (portfolio.isDeleted()) {
      throw ApiException.rule("PORTFOLIO_ALREADY_ARCHIVED", "This portfolio has already been archived");
    }

    portfolio.setDeletedAt(LocalDateTime.now());
    portfolio.setUpdatedBy(actor.getId());
    Portfolio saved = portfolios.save(portfolio);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PORTFOLIO,
        AuditActions.DELETE,
        "Archived portfolio " + saved.getPortfolioCode() + " (" + saved.getName() + ")",
        "PORTFOLIO",
        saved.getId(),
        saved.getPortfolioCode());

    return detailOf(saved);
  }

  /**
   * Allocate a property to a book.
   *
   * <p>A property belongs to at most one book at a time. The check is against the property's open
   * allocation in <em>any</em> book, not merely this one, because the schema's partial unique index is
   * over the property: an asset that is already in the book twice - or in two books - would double-count
   * in every portfolio total, silently. A duplicate is a 409 {@code PROPERTY_ALREADY_IN_PORTFOLIO},
   * raised before anything is written.</p>
   */
  @Transactional
  public PortfolioDtos.PortfolioDetail allocate(UUID portfolioId, PortfolioDtos.AllocatePropertyRequest request) {
    User actor = accessScope.requireUser();
    Portfolio portfolio = require(portfolioId);

    Property property =
        properties
            .findByIdAndDeletedAtIsNull(request.propertyId())
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));

    // The rule the schema enforces with uq_portfolio_properties_open, said as a named refusal.
    if (allocations.findByPropertyIdAndDeallocatedAtIsNullAndDeletedAtIsNull(property.getId()).isPresent()) {
      throw ApiException.rule(
          "PROPERTY_ALREADY_IN_PORTFOLIO", ErrorMessages.friendly("PROPERTY_ALREADY_IN_PORTFOLIO"));
    }

    LocalDate allocatedAt = request.allocatedAt() == null ? LocalDate.now() : request.allocatedAt();

    PortfolioProperty allocation = new PortfolioProperty();
    allocation.setPortfolio(portfolio);
    allocation.setProperty(property);
    allocation.setAllocatedAt(allocatedAt);
    allocation.setDeallocatedAt(request.deallocatedAt());
    allocation.setNotes(request.notes());
    allocation.setCreatedBy(actor.getId());
    allocation.setUpdatedBy(actor.getId());
    assertPeriod(allocation.getAllocatedAt(), allocation.getDeallocatedAt());

    allocations.save(allocation);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PORTFOLIO,
        AuditActions.UPDATE,
        "Allocated property " + property.getPropertyCode() + " to portfolio " + portfolio.getPortfolioCode(),
        "PORTFOLIO",
        portfolio.getId(),
        portfolio.getPortfolioCode());

    return detailOf(portfolio);
  }

  /**
   * Deallocate a property from a book.
   *
   * <p>The allocation is closed by recording the date it left, not deleted, so the book's composition
   * at a past date stays reconstructable. A property with no open allocation in this book is a 404.</p>
   */
  @Transactional
  public PortfolioDtos.PortfolioDetail deallocate(UUID portfolioId, UUID propertyId) {
    User actor = accessScope.requireUser();
    Portfolio portfolio = require(portfolioId);

    PortfolioProperty allocation =
        allocations
            .findByPropertyIdAndDeallocatedAtIsNullAndDeletedAtIsNull(propertyId)
            .filter(open -> open.getPortfolio() != null && portfolioId.equals(open.getPortfolio().getId()))
            .orElseThrow(() -> ApiException.notFound("This property is not currently in that portfolio"));

    LocalDate deallocatedAt = LocalDate.now();
    assertPeriod(allocation.getAllocatedAt(), deallocatedAt);
    allocation.setDeallocatedAt(deallocatedAt);
    allocation.setUpdatedBy(actor.getId());
    allocations.save(allocation);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PORTFOLIO,
        AuditActions.UPDATE,
        "Deallocated property " + propertyId + " from portfolio " + portfolio.getPortfolioCode(),
        "PORTFOLIO",
        portfolio.getId(),
        portfolio.getPortfolioCode());

    return detailOf(portfolio);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private PortfolioDtos.PortfolioDetail detailOf(Portfolio portfolio) {
    List<PortfolioDtos.AllocationEntry> rows =
        allocations.findAllByPortfolioIdAndDeallocatedAtIsNullAndDeletedAtIsNull(portfolio.getId()).stream()
            .map(PortfolioDtos.AllocationEntry::from)
            .toList();
    return new PortfolioDtos.PortfolioDetail(
        PortfolioDtos.PortfolioSummary.from(portfolio, rows.size()), rows);
  }

  private Map<UUID, Long> openAllocationCounts() {
    Map<UUID, Long> counts = new HashMap<>();
    for (Object[] row : allocations.countOpenAllocationsByPortfolio()) {
      if (row == null || row.length < 2 || row[0] == null) continue;
      counts.put((UUID) row[0], ((Number) row[1]).longValue());
    }
    return counts;
  }

  /**
   * The occupancy percentage over the leasable stock, guarded against a zero denominator.
   *
   * <p>A book with no leasable units reads {@code 0%}, never {@code NaN}: a development book holding
   * land is a real state, and an occupancy percentage that cannot be computed is honestly zero.</p>
   */
  static int occupancyPercent(long occupied, long leasable) {
    return leasable <= 0 ? 0 : (int) Math.round((occupied * 100.0) / leasable);
  }

  /**
   * The achieved yield percentage: the annualised rent over the current valuation, guarded.
   *
   * <p>A book with a zero or absent valuation reads {@code 0.00}, never a divide-by-zero and never
   * {@code NaN}. The result is a percentage at the product's money scale, and it is a <em>derived,
   * achieved</em> figure - the class note insists it is never presented as the book's target.</p>
   */
  static BigDecimal achievedYieldPercent(BigDecimal annualisedRent, BigDecimal valuation) {
    if (valuation == null || valuation.signum() <= 0) {
      return MoneyPolicy.zero();
    }
    BigDecimal rent = annualisedRent == null ? BigDecimal.ZERO : annualisedRent;
    return rent.multiply(BigDecimal.valueOf(100))
        .divide(valuation, MoneyPolicy.SCALE, RoundingMode.HALF_UP);
  }

  private static void assertClosedCarriesReason(PortfolioStatus status, String notes) {
    if (status == PortfolioStatus.CLOSED && (notes == null || notes.isBlank())) {
      throw ApiException.badRequest(
          "A closed portfolio must record why it was closed; a closed book cannot silently re-enter "
              + "the firm's live totals");
    }
  }

  private static void assertPeriod(LocalDate allocatedAt, LocalDate deallocatedAt) {
    if (allocatedAt != null && deallocatedAt != null && deallocatedAt.isBefore(allocatedAt)) {
      throw ApiException.badRequest("A property cannot be deallocated before it was allocated");
    }
  }

  private Owner requireOwner(UUID id) {
    return owners
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OWNER_NOT_FOUND")));
  }

  private User requireManager(UUID id) {
    return users
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("That manager does not exist"));
  }

  private static BigDecimal monthly(Lease lease) {
    BigDecimal amount = lease.getRentAmount() == null ? BigDecimal.ZERO : lease.getRentAmount();
    int months = lease.getRentFrequency() == null ? 1 : Math.max(1, lease.getRentFrequency().monthsPerPeriod());
    return MoneyPolicy.money(amount.divide(BigDecimal.valueOf(months), MoneyPolicy.SCALE, RoundingMode.HALF_UP));
  }

  private static boolean matchesSearch(Portfolio portfolio, String term) {
    return contains(portfolio.getName(), term)
        || contains(portfolio.getPortfolioCode(), term)
        || contains(portfolio.getDescription(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  /** Mints a book code from the record's own identity. See the class note on numbering. */
  private static String mintCode() {
    return "ME-PF-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private static PortfolioStatus parseStatus(String value, PortfolioStatus fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return PortfolioStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a portfolio status");
    }
  }

  private static PortfolioStrategy parseStrategy(String value, PortfolioStrategy fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return PortfolioStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(
          "'" + value + "' is not a portfolio strategy", PortfolioDtos.strategyValues());
    }
  }

  // --------------------------------------------------------------------------
  // Paging and sorting, applied here because the combined repository query does
  // not exist (see the class note on paging).
  // --------------------------------------------------------------------------

  private static List<Portfolio> sortPortfolios(List<Portfolio> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Portfolio> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Portfolio> next = portfolioComparator(order.getProperty(), order.isDescending());
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Portfolio> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<Portfolio> portfolioComparator(String property, boolean descending) {
    return switch (property == null ? "" : property) {
      case "name" -> comparingNullsLast(portfolio -> lower(portfolio.getName()), descending);
      case "portfolioCode" ->
          comparingNullsLast(portfolio -> lower(portfolio.getPortfolioCode()), descending);
      case "strategy" ->
          comparingNullsLast(
              portfolio -> portfolio.getStrategy() == null ? null : portfolio.getStrategy().name(),
              descending);
      case "status" ->
          comparingNullsLast(
              portfolio -> portfolio.getStatus() == null ? null : portfolio.getStatus().name(),
              descending);
      case "targetYieldPercent" -> comparingNullsLast(Portfolio::getTargetYieldPercent, descending);
      case "inceptionDate" -> comparingNullsLast(Portfolio::getInceptionDate, descending);
      case "updatedAt" -> comparingNullsLast(Portfolio::getUpdatedAt, descending);
      default -> comparingNullsLast(Portfolio::getCreatedAt, true);
    };
  }

  private static <T extends Comparable<? super T>> Comparator<Portfolio> comparingNullsLast(
      Function<Portfolio, T> key, boolean descending) {
    Comparator<T> order =
        descending
            ? Comparator.nullsLast(Comparator.<T>reverseOrder())
            : Comparator.nullsLast(Comparator.<T>naturalOrder());
    return Comparator.comparing(key, order);
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  private static <T> Page<T> pageOf(List<T> all, Pageable pageable) {
    int total = all.size();
    int size = pageable.isPaged() ? pageable.getPageSize() : total;
    int start = pageable.isPaged() ? (int) Math.min(pageable.getOffset(), (long) total) : 0;
    int end = Math.min(start + size, total);
    return new PageImpl<>(new ArrayList<>(all.subList(start, end)), pageable, total);
  }
}
