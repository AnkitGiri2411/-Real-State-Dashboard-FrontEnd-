package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Building;
import com.meridian.realestate.domain.MaintenanceCategory;
import com.meridian.realestate.domain.MaintenancePriority;
import com.meridian.realestate.domain.MaintenanceRequest;
import com.meridian.realestate.domain.MaintenanceStatus;
import com.meridian.realestate.domain.MaintenanceStatusHistory;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Vendor;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.MaintenanceDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuildingRepository;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.MaintenanceStatusHistoryRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.repository.VendorRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faults on the stock: who holds them, where they stand, and what they cost.
 *
 * <h2>Why this class exists</h2>
 * A maintenance request is the unit of work on a property's condition, and almost nothing about it is
 * a field edit. It moves through a lifecycle that the schema constrains tightly (an assigned request
 * names its holder, work that has started records when, a resolved request records how and when,
 * closure cannot precede resolution), it is handed to one of two kinds of holder - an empanelled
 * trade or a colleague - and the cost it carries is the figure the expense ledger traces a property's
 * maintenance spend back to. Those are one subject, and stating them in one place is what stops two
 * screens disagreeing about whether a fault is late, who holds it, or what fixing it cost.
 *
 * <h2>Status changes are operations, not edits</h2>
 * There is no method here that writes a status from a request field. A request moves only through
 * {@link #transition}, which validates the move against the map in {@link #ALLOWED} and refuses an
 * illegal one with {@code MAINTENANCE_TRANSITION_NOT_ALLOWED} (409). Resolution is its own operation
 * ({@link #resolve}) because the schema insists the resolution moment and the resolution notes are
 * written together, and because that is the moment the actual cost is recorded.
 *
 * <p>The map is deliberately a strict reading of the lifecycle the schema states:
 * {@code OPEN -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED}, with {@code CANCELLED} reachable
 * from the three outstanding states and nothing else. {@code CLOSED} and {@code CANCELLED} are
 * terminal and frozen - a request that has been closed cannot be reopened, because the trail of what
 * happened is worth more than the convenience of editing it back - and {@code OPEN} is reachable
 * again from {@code ASSIGNED} so that work returned to the desk is a recorded move rather than a
 * silent unassignment.</p>
 *
 * <p><b>Every change writes a {@link MaintenanceStatusHistory} row</b>, including the first one: the
 * schema makes {@code from_status} nullable precisely so that a request's chronology can start at
 * nothing becoming {@code OPEN}. The current status alone cannot answer the questions operations
 * asks - how long a fault sat unassigned, whether it was returned to the desk, who moved it - and
 * those are differences between consecutive rows, which are unrecoverable once only the latest state
 * is kept.</p>
 *
 * <h2>Empanelment: the exact rule</h2>
 * A vendor may be handed a request only when it is <b>empanelled</b>, its <b>status is
 * {@code ACTIVE}</b>, and its <b>category is the request's category</b>. The first two are the
 * schema's own facts; the third is why the two category enums exist as a shared vocabulary. A vendor
 * that fails any of them is refused with {@code VENDOR_NOT_EMPANELLED} - the approved-list rule,
 * named as the schema and the message catalogue name it - or, when it is the status that failed,
 * {@code VENDOR_NOT_ASSIGNABLE}, because "stopped for cause" and "not approved for this trade" are
 * different refusals and a reader should be able to tell them apart. The check happens before
 * anything is written, so a refused assignment leaves neither the request nor a status row touched.
 * Note that an <em>internal</em> holder is not subject to any of this: the firm's own facilities team
 * has no empanelment to check.
 *
 * <h2>Cost</h2>
 * {@code estimatedCost} and {@code actualCost} are kept apart and are never forced to agree: the
 * estimate is what the approval conversation was about, the actual is what it cost, and the variance
 * is itself a fact. Only {@link #resolve} writes the actual, and it writes it at the product's money
 * scale through {@link MoneyPolicy}-equivalent rounding, so a cost is never carried at a third
 * decimal place.
 *
 * <h2>Late and overdue</h2>
 * A request is <b>late</b> when it is still {@code OPEN} - which is exactly "nobody has answered it",
 * because the schema refuses {@code ASSIGNED} without a holder - more than
 * {@code app.portfolio.maintenance-urgent-hours} after it was reported. It is <b>overdue</b> when it
 * is still outstanding and is late or has passed its own target date. Both are computed in
 * {@link MaintenanceDtos} so the register, the detail and the summary cannot disagree.
 *
 * <h2>A note on paging</h2>
 * {@code MaintenanceRequestRepository} offers the board read ({@code findAllForBoard}) and separate
 * counted reads, but not one combined paged query for this register's eight filters. Rather than put
 * a literal {@code order by} in a query - which this product forbids, because it silently overrides
 * the order the caller asked for - the board is filtered, sorted and sliced here, so the page the
 * caller asked for is the page they get, with correct totals. The missing combined repository method
 * is reported rather than added, because the repository layer is not this task's to change. Likewise
 * there is no grouped-by-month query for {@code GET /cost-trend}; the trend is bucketed here from the
 * same {@code reported_at} axis {@code sumActualCostBetween} sums over, so the two figures agree.
 */
@Service
public class MaintenanceService {

  /** The permitted status moves. See the class note: the map is the rule, not a suggestion. */
  private static final Map<MaintenanceStatus, Set<MaintenanceStatus>> ALLOWED =
      new EnumMap<>(MaintenanceStatus.class);

  static {
    ALLOWED.put(
        MaintenanceStatus.OPEN,
        EnumSet.of(
            MaintenanceStatus.ASSIGNED, MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.CANCELLED));
    ALLOWED.put(
        MaintenanceStatus.ASSIGNED,
        EnumSet.of(
            MaintenanceStatus.OPEN, MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.CANCELLED));
    ALLOWED.put(
        MaintenanceStatus.IN_PROGRESS,
        EnumSet.of(MaintenanceStatus.RESOLVED, MaintenanceStatus.CANCELLED));
    ALLOWED.put(MaintenanceStatus.RESOLVED, EnumSet.of(MaintenanceStatus.CLOSED));
    // Terminal, and frozen. A closed or dropped fault has a trail worth more than an edit back.
    ALLOWED.put(MaintenanceStatus.CLOSED, EnumSet.noneOf(MaintenanceStatus.class));
    ALLOWED.put(MaintenanceStatus.CANCELLED, EnumSet.noneOf(MaintenanceStatus.class));
  }

  /**
   * The open set of {@link MaintenanceStatus#isOpen()} - the predicate of the schema's partial index
   * {@code ix_maintenance_requests_open} and of the dashboard's open-work count.
   */
  public static final Set<MaintenanceStatus> OPEN_STATUSES =
      EnumSet.of(MaintenanceStatus.OPEN, MaintenanceStatus.ASSIGNED, MaintenanceStatus.IN_PROGRESS);

  /** The urgent set of {@link MaintenancePriority#isUrgent()}. */
  public static final Set<MaintenancePriority> URGENT_PRIORITIES =
      EnumSet.of(MaintenancePriority.HIGH, MaintenancePriority.URGENT);

  /** The board's order of the kanban columns, so a chart does not reorder itself per request. */
  private static final List<MaintenanceStatus> STATUS_ORDER =
      List.of(
          MaintenanceStatus.OPEN,
          MaintenanceStatus.ASSIGNED,
          MaintenanceStatus.IN_PROGRESS,
          MaintenanceStatus.RESOLVED,
          MaintenanceStatus.CLOSED,
          MaintenanceStatus.CANCELLED);

  private static final List<MaintenancePriority> PRIORITY_ORDER =
      List.of(
          MaintenancePriority.URGENT,
          MaintenancePriority.HIGH,
          MaintenancePriority.MEDIUM,
          MaintenancePriority.LOW);

  private static final Map<String, Comparator<MaintenanceRequest>> SORTS =
      Map.ofEntries(
          Map.entry("createdAt", Comparator.comparing(MaintenanceRequest::getCreatedAt, nullSafe())),
          Map.entry("updatedAt", Comparator.comparing(MaintenanceRequest::getUpdatedAt, nullSafe())),
          Map.entry("requestCode", Comparator.comparing(MaintenanceRequest::getRequestCode, nullSafe())),
          Map.entry("reportedAt", Comparator.comparing(MaintenanceRequest::getReportedAt, nullSafe())),
          Map.entry("targetDate", Comparator.comparing(MaintenanceRequest::getTargetDate, nullSafe())),
          Map.entry("title", Comparator.comparing(MaintenanceRequest::getTitle, nullSafe())),
          Map.entry(
              "priority",
              Comparator.comparing((MaintenanceRequest r) -> enumName(r.getPriority()), nullSafe())),
          Map.entry(
              "status",
              Comparator.comparing((MaintenanceRequest r) -> enumName(r.getStatus()), nullSafe())),
          Map.entry(
              "category",
              Comparator.comparing((MaintenanceRequest r) -> enumName(r.getCategory()), nullSafe())),
          Map.entry(
              "property.name",
              Comparator.comparing(
                  (MaintenanceRequest r) ->
                      r.getProperty() == null ? null : r.getProperty().getName(),
                  nullSafe())),
          Map.entry(
              "assignedVendor.name",
              Comparator.comparing(
                  (MaintenanceRequest r) ->
                      r.getAssignedVendor() == null ? null : r.getAssignedVendor().getName(),
                  nullSafe())));

  private final MaintenanceRequestRepository requests;
  private final MaintenanceStatusHistoryRepository history;
  private final VendorRepository vendors;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final BuildingRepository buildings;
  private final TenantRepository tenants;
  private final UserRepository users;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;
  private final AppProperties appProperties;

  public MaintenanceService(
      MaintenanceRequestRepository requests,
      MaintenanceStatusHistoryRepository history,
      VendorRepository vendors,
      PropertyRepository properties,
      UnitRepository units,
      BuildingRepository buildings,
      TenantRepository tenants,
      UserRepository users,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope,
      AppProperties appProperties) {
    this.requests = requests;
    this.history = history;
    this.vendors = vendors;
    this.properties = properties;
    this.units = units;
    this.buildings = buildings;
    this.tenants = tenants;
    this.users = users;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
    this.appProperties = appProperties;
  }

  // ---------------------------------------------------------------------------
  // The lifecycle, exposed so a screen can offer only the moves that will succeed
  // ---------------------------------------------------------------------------

  public static boolean canTransition(MaintenanceStatus from, MaintenanceStatus to) {
    if (from == null || to == null || from == to) return false;
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  public static Set<MaintenanceStatus> nextStatuses(MaintenanceStatus from) {
    return ALLOWED.getOrDefault(from, Set.of());
  }

  /** True when the status has no legal move out of it. */
  public static boolean isTerminal(MaintenanceStatus status) {
    return status != null && nextStatuses(status).isEmpty();
  }

  private static Set<String> transitionsFor(MaintenanceStatus status) {
    Set<String> names = new LinkedHashSet<>();
    for (MaintenanceStatus next : nextStatuses(status)) names.add(next.name());
    return names;
  }

  private int urgentHours() {
    return appProperties.getPortfolio().getMaintenanceUrgentHours();
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** The live request behind an id, or a 404. Archived requests are not addressable. */
  @Transactional(readOnly = true)
  public MaintenanceRequest require(UUID requestId) {
    return requests
        .findByIdAndDeletedAtIsNull(requestId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("MAINTENANCE_NOT_FOUND")));
  }

  /** The board, filtered, searched, sorted and paged. */
  @Transactional(readOnly = true)
  public PageResponse<MaintenanceDtos.MaintenanceSummary> list(
      UUID propertyId,
      UUID unitId,
      String status,
      String priority,
      String category,
      UUID assignedVendorId,
      UUID assignedToId,
      LocalDate reportedFrom,
      LocalDate reportedTo,
      Boolean overdueOnly,
      String search,
      Pageable pageable) {

    MaintenanceStatus statusFilter = blank(status) ? null : parseStatus(status);
    MaintenancePriority priorityFilter = blank(priority) ? null : parsePriority(priority);
    MaintenanceCategory categoryFilter = blank(category) ? null : parseCategory(category);
    String term = blank(search) ? null : search.trim().toLowerCase(Locale.ROOT);
    boolean overdueFilter = Boolean.TRUE.equals(overdueOnly);
    LocalDateTime now = LocalDateTime.now();
    int hours = urgentHours();

    List<MaintenanceDtos.MaintenanceSummary> rows =
        requests.findAllForBoard().stream()
            .filter(request -> propertyId == null || matchesProperty(request, propertyId))
            .filter(request -> unitId == null || matchesUnit(request, unitId))
            .filter(request -> statusFilter == null || statusFilter == request.getStatus())
            .filter(request -> priorityFilter == null || priorityFilter == request.getPriority())
            .filter(request -> categoryFilter == null || categoryFilter == request.getCategory())
            .filter(request -> assignedVendorId == null || matchesVendor(request, assignedVendorId))
            .filter(request -> assignedToId == null || matchesAssignee(request, assignedToId))
            .filter(request -> withinReported(request, reportedFrom, reportedTo))
            .filter(request -> !overdueFilter || MaintenanceDtos.isOverdue(request, now, hours))
            .filter(request -> term == null || matchesSearch(request, term))
            .sorted(comparator(pageable))
            .map(request -> MaintenanceDtos.MaintenanceSummary.from(request, now, hours))
            .toList();

    return paginate(rows, pageable);
  }

  /** One request, with its status trail. */
  @Transactional(readOnly = true)
  public MaintenanceDtos.MaintenanceDetail detail(UUID requestId) {
    return detailOf(require(requestId));
  }

  /**
   * The operations card.
   *
   * <p>The two counts are the repository's own, taken over the open set and the urgent set this class
   * declares, so the number on the card is the number the schema's partial index was built to
   * produce. The late count is the product's threshold applied to the same board, and the cost is the
   * actual spend to date - never the estimate, which is what somebody hoped rather than what the
   * portfolio paid.</p>
   */
  @Transactional(readOnly = true)
  public MaintenanceDtos.MaintenanceSummaryResponse summary() {
    LocalDateTime now = LocalDateTime.now();
    int hours = urgentHours();

    long openCount = requests.countByStatusInAndDeletedAtIsNull(OPEN_STATUSES);
    long urgentOpenCount =
        requests.countByStatusInAndPriorityInAndDeletedAtIsNull(OPEN_STATUSES, URGENT_PRIORITIES);

    Map<MaintenanceStatus, Long> statusCounts = countsByStatus();
    Map<MaintenancePriority, Long> priorityCounts = countsByPriority();

    BigDecimal costToDate =
        safe(requests.sumActualCostBetween(LocalDateTime.of(1970, 1, 1, 0, 0), now));
    BigDecimal estimatedOpenCost =
        requests.findAllForBoard().stream()
            .filter(MaintenanceRequest::isOpen)
            .map(request -> safe(request.getEstimatedCost()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    long lateOpenCount =
        requests.findAllForBoard().stream()
            .filter(request -> MaintenanceDtos.isLate(request, now, hours))
            .count();

    List<MaintenanceDtos.StatusBucket> byStatus = new ArrayList<>();
    for (MaintenanceStatus status : STATUS_ORDER) {
      byStatus.add(
          new MaintenanceDtos.StatusBucket(
              status.name(),
              labelOf(status.name()),
              statusCounts.getOrDefault(status, 0L),
              status.isOpen()));
    }

    List<MaintenanceDtos.PriorityBucket> byPriority = new ArrayList<>();
    for (MaintenancePriority priority : PRIORITY_ORDER) {
      byPriority.add(
          new MaintenanceDtos.PriorityBucket(
              priority.name(), priorityCounts.getOrDefault(priority, 0L), priority.isUrgent()));
    }

    return new MaintenanceDtos.MaintenanceSummaryResponse(
        openCount,
        urgentOpenCount,
        lateOpenCount,
        money(costToDate),
        money(estimatedOpenCost),
        now,
        byStatus,
        byPriority);
  }

  /**
   * What maintenance has cost, month by month.
   *
   * <p>Bucketed on {@code reported_at}, which is the axis {@code sumActualCostBetween} sums over -
   * the moment the fault entered the books. Using the resolution moment instead would be defensible
   * in isolation and would make the trend disagree with the period figure on the summary card, and a
   * report that disagrees with the number beside it is worse than either choice on its own. The
   * default window is the configured trend length, so a caller that asks for nothing gets the same
   * span the rest of the product charts.</p>
   */
  @Transactional(readOnly = true)
  public MaintenanceDtos.CostTrendResponse costTrend(LocalDate from, LocalDate to) {
    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate start =
        from == null ? end.minusMonths(Math.max(appProperties.getPortfolio().getTrendMonths(), 1) - 1L) : from;
    if (start.isAfter(end)) {
      throw ApiException.badRequest("The start of the period cannot fall after its end");
    }

    LocalDateTime startAt = start.atStartOfDay();
    LocalDateTime endAt = end.atTime(LocalTime.MAX);

    Map<YearMonth, BigDecimal> cost = new TreeMap<>();
    Map<YearMonth, Long> resolved = new TreeMap<>();
    for (YearMonth month : monthsBetween(start, end)) {
      cost.put(month, BigDecimal.ZERO);
      resolved.put(month, 0L);
    }

    for (MaintenanceRequest request : requests.findAllForBoard()) {
      LocalDateTime reportedAt = request.getReportedAt();
      if (reportedAt == null || reportedAt.isBefore(startAt) || reportedAt.isAfter(endAt)) continue;
      YearMonth month = YearMonth.from(reportedAt);
      cost.merge(month, safe(request.getActualCost()), BigDecimal::add);
      if (request.getResolvedAt() != null) resolved.merge(month, 1L, Long::sum);
    }

    List<MaintenanceDtos.CostTrendPoint> points = new ArrayList<>();
    for (Map.Entry<YearMonth, BigDecimal> entry : cost.entrySet()) {
      YearMonth month = entry.getKey();
      points.add(
          new MaintenanceDtos.CostTrendPoint(
              month.getYear(),
              month.getMonthValue(),
              month.getMonth().name().substring(0, 3) + " " + month.getYear(),
              money(entry.getValue()),
              resolved.getOrDefault(month, 0L)));
    }

    BigDecimal total =
        points.stream().map(MaintenanceDtos.CostTrendPoint::actualCost).reduce(BigDecimal.ZERO, BigDecimal::add);

    return new MaintenanceDtos.CostTrendResponse(
        start, end, money(total), resolved.values().stream().mapToLong(Long::longValue).sum(), points, LocalDateTime.now());
  }

  // ---------------------------------------------------------------------------
  // The operations
  // ---------------------------------------------------------------------------

  /** Raise a request. Every request starts at OPEN, whatever a caller may have hoped. */
  @Transactional
  public MaintenanceDtos.MaintenanceDetail create(MaintenanceDtos.CreateMaintenanceRequest request) {
    User actor = accessScope.requireUser();

    Property property = requireProperty(request.propertyId());
    Unit unit = request.unitId() == null ? null : requireUnit(request.unitId());
    Building building = request.buildingId() == null ? null : requireBuilding(request.buildingId());
    Tenant tenant =
        request.reportedByTenantId() == null ? null : requireTenant(request.reportedByTenantId());
    assertTarget(property, unit, building);

    LocalDateTime now = LocalDateTime.now();

    MaintenanceRequest entity = new MaintenanceRequest();
    entity.setRequestCode(numbers.next(BusinessNumberService.MAINTENANCE));
    entity.setProperty(property);
    entity.setUnit(unit);
    entity.setBuilding(building);
    entity.setCategory(parseCategory(request.category()));
    entity.setPriority(
        request.priority() == null || request.priority().isBlank()
            ? MaintenancePriority.MEDIUM
            : parsePriority(request.priority()));
    entity.setStatus(MaintenanceStatus.OPEN);
    entity.setTitle(request.title().trim());
    entity.setDescription(request.description());
    entity.setReportedBy(actor);
    entity.setReportedByTenant(tenant);
    entity.setReporterName(trim(request.reporterName()));
    entity.setReportedAt(now);
    entity.setTargetDate(request.targetDate());
    entity.setEstimatedCost(
        request.estimatedCost() == null ? null : MoneyPolicy.money(request.estimatedCost()));
    entity.setChargeableToTenant(Boolean.TRUE.equals(request.chargeableToTenant()));
    entity.setCreatedBy(actor.getId());
    entity.setUpdatedBy(actor.getId());

    MaintenanceRequest saved = requests.save(entity);

    // The first row of the trail: nothing became OPEN. from_status is nullable for exactly this.
    writeHistory(saved, null, MaintenanceStatus.OPEN, "Request raised", actor, now);

    recordAudit(
        actor,
        AuditActions.CREATE,
        "Raised " + saved.getCategory() + " request " + saved.getRequestCode()
            + " at " + property.getPropertyCode(),
        saved);

    return detailOf(saved);
  }

  /**
   * Change a request's details.
   *
   * <p>Descriptive fields stay correctable for the life of the record - a title typed wrong is still
   * wrong after the fault is fixed - but this never writes the status, the actual cost or the
   * resolution notes, because those are operations with their own endpoints and their own
   * consequences. The estimate is editable on purpose: it is what the work is expected to cost, and
   * a second opinion on that is a normal thing to record.</p>
   */
  @Transactional
  public MaintenanceDtos.MaintenanceDetail update(
      UUID requestId, MaintenanceDtos.UpdateMaintenanceRequest request) {
    User actor = accessScope.requireUser();
    MaintenanceRequest entity = require(requestId);

    if (!blank(request.category())) entity.setCategory(parseCategory(request.category()));
    if (!blank(request.priority())) entity.setPriority(parsePriority(request.priority()));
    if (!blank(request.title())) entity.setTitle(request.title().trim());
    if (request.description() != null) entity.setDescription(request.description());
    if (request.unitId() != null) entity.setUnit(requireUnit(request.unitId()));
    if (request.buildingId() != null) entity.setBuilding(requireBuilding(request.buildingId()));
    if (request.reportedByTenantId() != null) {
      entity.setReportedByTenant(requireTenant(request.reportedByTenantId()));
    }
    if (request.reporterName() != null) entity.setReporterName(trim(request.reporterName()));
    if (request.targetDate() != null) entity.setTargetDate(request.targetDate());
    if (request.estimatedCost() != null) {
      entity.setEstimatedCost(MoneyPolicy.money(request.estimatedCost()));
    }
    if (request.chargeableToTenant() != null) {
      entity.setChargeableToTenant(request.chargeableToTenant());
    }
    assertTarget(entity.getProperty(), entity.getUnit(), entity.getBuilding());
    entity.setUpdatedBy(actor.getId());

    MaintenanceRequest saved = requests.save(entity);
    recordAudit(actor, AuditActions.UPDATE, "Changed request " + saved.getRequestCode(), saved);
    return detailOf(saved);
  }

  /**
   * Hand the work to a vendor or a colleague.
   *
   * <p>An {@code OPEN} request becomes {@code ASSIGNED} and the move is written to the trail; a
   * request already {@code ASSIGNED} simply changes holder, which is a reassignment rather than a
   * status change and is recorded in the audit trail (as {@code ASSIGNMENT}) rather than in the
   * status history. A settled request cannot be handed to anybody: {@code RESOLVED}, {@code CLOSED}
   * and {@code CANCELLED} are frozen, and an assignment onto one of them would put a holder against
   * work that is not going to happen. The vendor is validated before anything is written, so a
   * non-empanelled trade leaves no trace of a hand-over that never happened.</p>
   */
  @Transactional
  public MaintenanceDtos.MaintenanceDetail assign(
      UUID requestId, MaintenanceDtos.AssignMaintenanceRequest request) {
    User actor = accessScope.requireUser();
    MaintenanceRequest entity = require(requestId);

    MaintenanceStatus from = entity.getStatus();
    if (from == null || !from.isOpen()) {
      throw ApiException.rule(
          "MAINTENANCE_TRANSITION_NOT_ALLOWED",
          "A request that is " + from + " cannot be handed to anybody; only an outstanding request "
              + "can be assigned.");
    }
    if (request.vendorId() == null && request.assignedToId() == null) {
      throw ApiException.badRequest(
          "An assignment must name the vendor or the colleague who is taking the work");
    }

    Vendor vendor =
        request.vendorId() == null
            ? null
            : requireAssignableVendor(request.vendorId(), entity.getCategory());
    User assignee = request.assignedToId() == null ? null : requireUser(request.assignedToId());

    LocalDateTime now = LocalDateTime.now();
    entity.setAssignedVendor(vendor);
    entity.setAssignedTo(assignee);
    entity.setAssignedAt(now);
    if (from == MaintenanceStatus.OPEN) {
      entity.setStatus(MaintenanceStatus.ASSIGNED);
    }
    entity.setUpdatedBy(actor.getId());

    MaintenanceRequest saved = requests.save(entity);

    if (from != saved.getStatus()) {
      writeHistory(saved, from, saved.getStatus(), request.reason(), actor, now);
    }

    recordAudit(
        actor,
        AuditActions.ASSIGNMENT,
        "Assigned request " + saved.getRequestCode() + " to " + holderName(saved),
        saved,
        from == null ? null : from.name(),
        saved.getStatus() == null ? null : saved.getStatus().name());

    return detailOf(saved);
  }

  /**
   * Move a request between statuses.
   *
   * <p>Resolution is deliberately <em>not</em> reachable here. A resolved request has to carry the
   * moment it was fixed, what was done about it and - in the ordinary case - what it cost, and a
   * status endpoint that could write the first two while silently dropping the third is how a
   * property's maintenance spend becomes untraceable. A move to {@code RESOLVED} is therefore refused
   * with a named conflict that points at {@link #resolve}.</p>
   */
  @Transactional
  public MaintenanceDtos.MaintenanceDetail transition(
      UUID requestId, MaintenanceDtos.StatusTransitionRequest request) {
    User actor = accessScope.requireUser();
    MaintenanceRequest entity = require(requestId);

    MaintenanceStatus from = entity.getStatus();
    MaintenanceStatus target = parseStatus(request.status());

    if (target == MaintenanceStatus.RESOLVED) {
      throw ApiException.rule(
          "MAINTENANCE_TRANSITION_NOT_ALLOWED",
          "A request is resolved through the resolve operation, so that the moment it was fixed, "
              + "what was done and what it cost are recorded together.");
    }
    if (!canTransition(from, target)) {
      throw ApiException.rule(
          "MAINTENANCE_TRANSITION_NOT_ALLOWED",
          "A request cannot move from " + from + " to " + target + "."
              + (isTerminal(from)
                  ? " A " + from + " request has reached a terminal state and cannot move again."
                  : " From " + from + " the possible moves are " + nextStatuses(from) + "."));
    }
    if (target == MaintenanceStatus.ASSIGNED
        && entity.getAssignedVendor() == null
        && entity.getAssignedTo() == null) {
      // The schema's own rule, surfaced as a named refusal: work does not sit in ASSIGNED with nobody
      // holding it.
      throw ApiException.rule(
          "MAINTENANCE_TRANSITION_NOT_ALLOWED",
          "An assigned request names the vendor or colleague holding it; assign the work first.");
    }

    LocalDateTime now = LocalDateTime.now();
    switch (target) {
      case IN_PROGRESS -> {
        if (entity.getStartedAt() == null) entity.setStartedAt(now);
      }
      case ASSIGNED -> {
        if (entity.getAssignedAt() == null) entity.setAssignedAt(now);
      }
      case CLOSED -> {
        if (entity.getResolvedAt() == null) {
          throw ApiException.rule(
              "MAINTENANCE_TRANSITION_NOT_ALLOWED",
              "A request cannot be closed before it was resolved; closure cannot precede resolution.");
        }
        entity.setClosedAt(now);
      }
      default -> {
        // OPEN (work returned to the desk) and CANCELLED carry no extra fact.
      }
    }

    entity.setStatus(target);
    entity.setUpdatedBy(actor.getId());
    MaintenanceRequest saved = requests.save(entity);

    writeHistory(saved, from, target, request.reason(), actor, now);
    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Request " + saved.getRequestCode() + " " + from + " -> " + target,
        saved,
        from == null ? null : from.name(),
        target.name());

    return detailOf(saved);
  }

  /**
   * Record what was done about a fault and what it cost.
   *
   * <p>Reachable only from {@code IN_PROGRESS}, which is what the lifecycle states, and refused for a
   * request that is already resolved or closed. The actual cost is stored at the product's money scale
   * and is deliberately <em>not</em> reconciled against the estimate: the two figures answer different
   * questions and the variance between them is a fact the operations review reads. Notes are required,
   * because a resolution with nothing said about it is indistinguishable from a status somebody
   * clicked.</p>
   */
  @Transactional
  public MaintenanceDtos.MaintenanceDetail resolve(
      UUID requestId, MaintenanceDtos.ResolveMaintenanceRequest request) {
    User actor = accessScope.requireUser();
    MaintenanceRequest entity = require(requestId);

    MaintenanceStatus from = entity.getStatus();
    if (!canTransition(from, MaintenanceStatus.RESOLVED)) {
      throw ApiException.rule(
          "MAINTENANCE_TRANSITION_NOT_ALLOWED",
          "A request cannot move from " + from + " to RESOLVED. From " + from + " the possible moves "
              + "are " + nextStatuses(from) + ".");
    }
    if (blank(request.resolutionNotes())) {
      throw ApiException.badRequest("A resolved request must record what was done about it");
    }

    LocalDateTime now = LocalDateTime.now();
    entity.setResolutionNotes(request.resolutionNotes().trim());
    entity.setResolvedAt(now);
    if (request.actualCost() != null) {
      entity.setActualCost(MoneyPolicy.money(request.actualCost()));
    }
    entity.setStatus(MaintenanceStatus.RESOLVED);
    entity.setUpdatedBy(actor.getId());

    MaintenanceRequest saved = requests.save(entity);

    writeHistory(
        saved,
        from,
        MaintenanceStatus.RESOLVED,
        blank(request.reason()) ? "Resolved" : request.reason(),
        actor,
        now);
    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Resolved request " + saved.getRequestCode()
            + (saved.getActualCost() == null ? "" : " at a cost of " + saved.getActualCost()),
        saved,
        from == null ? null : from.name(),
        MaintenanceStatus.RESOLVED.name());

    return detailOf(saved);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private MaintenanceDtos.MaintenanceDetail detailOf(MaintenanceRequest entity) {
    LocalDateTime now = LocalDateTime.now();
    int hours = urgentHours();
    UUID requestId = entity.getId();

    List<MaintenanceDtos.StatusHistoryEntry> trail =
        requestId == null
            ? List.of()
            : history.findAllByRequest_IdOrderByChangedAtDesc(requestId).stream()
                .map(
                    entry ->
                        new MaintenanceDtos.StatusHistoryEntry(
                            entry.getId(),
                            entry.getFromStatus() == null ? null : entry.getFromStatus().name(),
                            entry.getToStatus() == null ? null : entry.getToStatus().name(),
                            entry.getReason(),
                            entry.getChangedBy() == null ? null : entry.getChangedBy().getId(),
                            entry.getChangedAt()))
                .toList();

    return new MaintenanceDtos.MaintenanceDetail(
        entity.getId(),
        entity.getRequestCode(),
        entity.getProperty() == null ? null : entity.getProperty().getId(),
        entity.getProperty() == null ? null : entity.getProperty().getPropertyCode(),
        entity.getProperty() == null ? null : entity.getProperty().getName(),
        entity.getUnit() == null ? null : entity.getUnit().getId(),
        entity.getUnit() == null ? null : entity.getUnit().getUnitCode(),
        entity.getBuilding() == null ? null : entity.getBuilding().getId(),
        entity.getCategory() == null ? null : entity.getCategory().name(),
        entity.getPriority() == null ? null : entity.getPriority().name(),
        entity.getStatus() == null ? null : entity.getStatus().name(),
        entity.getTitle(),
        entity.getDescription(),
        entity.getReportedBy() == null ? null : entity.getReportedBy().getId(),
        entity.getReportedBy() == null ? null : entity.getReportedBy().getFullName(),
        entity.getReportedByTenant() == null ? null : entity.getReportedByTenant().getId(),
        entity.getReporterName(),
        entity.getAssignedVendor() == null ? null : entity.getAssignedVendor().getId(),
        entity.getAssignedVendor() == null ? null : entity.getAssignedVendor().getName(),
        entity.getAssignedTo() == null ? null : entity.getAssignedTo().getId(),
        entity.getAssignedTo() == null ? null : entity.getAssignedTo().getFullName(),
        entity.getReportedAt(),
        entity.getTargetDate(),
        entity.getAssignedAt(),
        entity.getStartedAt(),
        entity.getResolvedAt(),
        entity.getClosedAt(),
        entity.getEstimatedCost(),
        entity.getActualCost(),
        entity.getResolutionNotes(),
        entity.isChargeableToTenant(),
        MaintenanceDtos.isLate(entity, now, hours),
        MaintenanceDtos.isOverdue(entity, now, hours),
        entity.isOpen(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        transitionsFor(entity.getStatus()),
        trail);
  }

  private void writeHistory(
      MaintenanceRequest request,
      MaintenanceStatus from,
      MaintenanceStatus to,
      String reason,
      User actor,
      LocalDateTime at) {
    MaintenanceStatusHistory row = new MaintenanceStatusHistory();
    row.setRequest(request);
    row.setFromStatus(from);
    row.setToStatus(to);
    row.setReason(trim(reason));
    row.setChangedBy(actor);
    row.setChangedAt(at);
    history.save(row);
  }

  private void recordAudit(
      User actor,
      String action,
      String description,
      MaintenanceRequest request,
      String previousValue,
      String newValue) {
    // The request's property and unit references are written through the audit entry's own columns,
    // which is what the property and unit screens' activity tabs read the trail by.
    audit.recordEntityChange(
        actor.getId(),
        actor.getFullName(),
        AuditActions.MAINTENANCE,
        action,
        description,
        "MAINTENANCE_REQUEST",
        request.getId(),
        request.getRequestCode(),
        previousValue,
        newValue,
        referencesFor(request));
  }

  private void recordAudit(User actor, String action, String description, MaintenanceRequest request) {
    recordAudit(actor, action, description, request, null, null);
  }

  /**
   * The audit entry's named references for a request.
   *
   * <p>Written as one helper so a call site cannot put the unit where the property belongs: the
   * columns are positional and several are null, and a single mis-ordered call would file an entry
   * against the wrong asset while still looking correct.</p>
   */
  private static AuditRecordingService.EntityReferences referencesFor(MaintenanceRequest request) {
    return new AuditRecordingService.EntityReferences(
        request.getProperty() == null ? null : request.getProperty().getId(),
        request.getUnit() == null ? null : request.getUnit().getId(),
        null,
        null,
        null,
        null,
        null,
        null,
        request.getId());
  }

  private Map<MaintenanceStatus, Long> countsByStatus() {
    Map<MaintenanceStatus, Long> counts = new EnumMap<>(MaintenanceStatus.class);
    for (MaintenanceRequestRepository.StatusCount row : requests.countGroupedByStatus()) {
      if (row == null || row.getStatus() == null) continue;
      counts.merge(row.getStatus(), row.getTotal(), Long::sum);
    }
    return counts;
  }

  private Map<MaintenancePriority, Long> countsByPriority() {
    Map<MaintenancePriority, Long> counts = new EnumMap<>(MaintenancePriority.class);
    for (MaintenanceRequestRepository.PriorityCount row : requests.countGroupedByPriority()) {
      if (row == null || row.getPriority() == null) continue;
      counts.merge(row.getPriority(), row.getTotal(), Long::sum);
    }
    return counts;
  }

  /**
   * The vendor that may hold this kind of work.
   *
   * <p>See the class note: empanelled, active, and of the request's own trade. The two refusals are
   * separate on purpose - "we stopped working with them" and "they are not approved for this trade"
   * are different conversations.</p>
   */
  private Vendor requireAssignableVendor(UUID vendorId, MaintenanceCategory category) {
    Vendor vendor =
        vendors
            .findByIdAndDeletedAtIsNull(vendorId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("VENDOR_NOT_FOUND")));

    if (!vendor.isAssignable()) {
      throw ApiException.rule(
          "VENDOR_NOT_ASSIGNABLE",
          vendor.getName() + " is " + vendor.getStatus() + " and cannot be given new work.");
    }
    if (!VendorService.isAvailableFor(vendor, category)) {
      throw ApiException.rule(
          "VENDOR_NOT_EMPANELLED",
          ErrorMessages.friendly("VENDOR_NOT_EMPANELLED")
              + " (" + vendor.getName() + " is registered for "
              + vendor.getCategory() + ", and this request is " + category + ".)");
    }
    return vendor;
  }

  private static String holderName(MaintenanceRequest request) {
    if (request.getAssignedVendor() != null) return request.getAssignedVendor().getName();
    if (request.getAssignedTo() != null) return request.getAssignedTo().getFullName();
    return "nobody";
  }

  private Property requireProperty(UUID id) {
    return properties
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  private Unit requireUnit(UUID id) {
    return units
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("UNIT_NOT_FOUND")));
  }

  private Building requireBuilding(UUID id) {
    return buildings
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("BUILDING_NOT_FOUND")));
  }

  private Tenant requireTenant(UUID id) {
    return tenants
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("TENANT_NOT_FOUND")));
  }

  private User requireUser(UUID id) {
    return users
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that member of staff"));
  }

  /** The fault's target is the property, and anything else it names has to belong to it. */
  private static void assertTarget(Property property, Unit unit, Building building) {
    if (unit != null) {
      if (property == null) {
        throw ApiException.badRequest("A unit cannot be set without its property");
      }
      if (unit.getProperty() == null || !property.getId().equals(unit.getProperty().getId())) {
        throw ApiException.badRequest("The unit does not belong to the chosen property");
      }
    }
    if (building != null) {
      if (property == null) {
        throw ApiException.badRequest("A building cannot be set without its property");
      }
      if (building.getProperty() == null || !property.getId().equals(building.getProperty().getId())) {
        throw ApiException.badRequest("The building does not belong to the chosen property");
      }
    }
  }

  private static boolean matchesProperty(MaintenanceRequest request, UUID propertyId) {
    return request.getProperty() != null && propertyId.equals(request.getProperty().getId());
  }

  private static boolean matchesUnit(MaintenanceRequest request, UUID unitId) {
    return request.getUnit() != null && unitId.equals(request.getUnit().getId());
  }

  private static boolean matchesVendor(MaintenanceRequest request, UUID vendorId) {
    return request.getAssignedVendor() != null && vendorId.equals(request.getAssignedVendor().getId());
  }

  private static boolean matchesAssignee(MaintenanceRequest request, UUID userId) {
    return request.getAssignedTo() != null && userId.equals(request.getAssignedTo().getId());
  }

  private static boolean matchesSearch(MaintenanceRequest request, String term) {
    return contains(request.getRequestCode(), term)
        || contains(request.getTitle(), term)
        || contains(request.getDescription(), term)
        || contains(request.getReporterName(), term)
        || contains(
            request.getProperty() == null ? null : request.getProperty().getName(), term)
        || contains(request.getUnit() == null ? null : request.getUnit().getUnitCode(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  private static boolean withinReported(
      MaintenanceRequest request, LocalDate from, LocalDate to) {
    LocalDateTime reportedAt = request.getReportedAt();
    if (from == null && to == null) return true;
    if (reportedAt == null) return false;
    LocalDate date = reportedAt.toLocalDate();
    if (from != null && date.isBefore(from)) return false;
    if (to != null && date.isAfter(to)) return false;
    return true;
  }

  private static List<YearMonth> monthsBetween(LocalDate from, LocalDate to) {
    List<YearMonth> months = new ArrayList<>();
    YearMonth cursor = YearMonth.from(from);
    YearMonth end = YearMonth.from(to);
    while (!cursor.isAfter(end)) {
      months.add(cursor);
      cursor = cursor.plusMonths(1);
    }
    return months;
  }

  private static String labelOf(String status) {
    return status.charAt(0) + status.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static BigDecimal safe(BigDecimal value) {
    return value == null ? MoneyPolicy.zero() : value;
  }

  private static BigDecimal money(BigDecimal value) {
    return MoneyPolicy.money(value);
  }

  private static MaintenanceStatus parseStatus(String value) {
    try {
      return MaintenanceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a maintenance status");
    }
  }

  private static MaintenancePriority parsePriority(String value) {
    try {
      return MaintenancePriority.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a maintenance priority");
    }
  }

  private static MaintenanceCategory parseCategory(String value) {
    try {
      return MaintenanceCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a maintenance category");
    }
  }

  private static <T extends Comparable<? super T>> Comparator<T> nullSafe() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  private static Comparator<MaintenanceRequest> comparator(Pageable pageable) {
    Comparator<MaintenanceRequest> tieBreak =
        Comparator.comparing(MaintenanceRequest::getCreatedAt, nullSafe())
            .thenComparing(MaintenanceRequest::getId, nullSafe());
    Comparator<MaintenanceRequest> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<MaintenanceRequest> by = SORTS.get(order.getProperty());
      if (by == null) continue;
      if (order.isDescending()) by = by.reversed();
      chosen = chosen == null ? by : chosen.thenComparing(by);
    }
    return chosen == null ? tieBreak.reversed() : chosen.thenComparing(tieBreak);
  }

  private static <T> PageResponse<T> paginate(List<T> items, Pageable pageable) {
    int size = pageable == null || pageable.getPageSize() < 1 ? 20 : pageable.getPageSize();
    int page = pageable == null ? 0 : pageable.getPageNumber();
    int from = Math.min(page * size, items.size());
    int to = Math.min(from + size, items.size());
    return PageResponse.of(items.subList(from, to), page, size, items.size());
  }
}
