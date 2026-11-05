package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.InvoiceStatus;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.LeaseRenewal;
import com.meridian.realestate.domain.LeaseRenewalStatus;
import com.meridian.realestate.domain.LeaseStatus;
import com.meridian.realestate.domain.LeaseStatusHistory;
import com.meridian.realestate.domain.LeaseTerm;
import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.PaymentAllocation;
import com.meridian.realestate.domain.PaymentStatus;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.RentFrequency;
import com.meridian.realestate.domain.RentInvoice;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.LeaseDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRenewalRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.LeaseStatusHistoryRepository;
import com.meridian.realestate.repository.LeaseTermRepository;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PaymentAllocationRepository;
import com.meridian.realestate.repository.PaymentRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.RentInvoiceRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lease: the contract that makes a unit occupied, its rent schedule, its status trail and its
 * renewals.
 *
 * <h2>The two rules this class owns that the schema cannot express</h2>
 * <p><b>One running lease per unit.</b> The partial unique index {@code uq_leases_one_running_per_unit}
 * enforces it in the database, but only for the rows it can see - and a lease that is created as a
 * {@code DRAFT}, or moved to {@code ACTIVE}, has to be checked against the unit's current tenancy
 * <em>before</em> the index ever fires. So the check is here, in one place, and its refusal is the
 * named {@code UNIT_ALREADY_LET} rather than the database's opaque constraint text.</p>
 *
 * <p><b>An explicit transition map.</b> A lease moves between states in specific ways, and the
 * order matters: you activate a lease before you can expire it, you renew it while it is running,
 * and you terminate it with a reason. The allowed moves are declared once, as an immutable map keyed
 * by {@link LeaseStatus}, and anything else is refused with {@code LEASE_TRANSITION_NOT_ALLOWED} -
 * rather than being allowed and quietly producing a lease whose {@code activated_at} contradicts its
 * status, which is exactly what {@code ck_leases_lifecycle} would then reject with a raw constraint
 * error.</p>
 *
 * <p><b>The lifecycle columns are set by the transition, not by the caller.</b> Entering a running
 * state stamps {@code activated_at}; entering {@code TERMINATED} stamps {@code terminated_at} and
 * requires a reason. That is what keeps the state machine and {@code ck_leases_lifecycle} in
 * agreement without the caller having to remember either.</p>
 */
@Service
public class LeaseService {

  private static final int NOTICE_PERIOD_DEFAULT_DAYS = 30;

  /**
   * The allowed transitions, declared once. The values are exactly the moves the lease's own
   * lifecycle constraint permits: a lease may be activated, may expire, may be renewed and may be
   * terminated, and the terminal states ({@code RENEWED}, {@code TERMINATED}, {@code CANCELLED})
   * lead nowhere. Nothing moves back to {@code DRAFT} or {@code PENDING} once it has been activated,
   * because {@code activated_at} cannot be cleared.
   */
  private static final Map<LeaseStatus, Set<LeaseStatus>> ALLOWED_TRANSITIONS =
      allowedTransitions();

  private final LeaseRepository leases;
  private final LeaseTermRepository terms;
  private final LeaseRenewalRepository renewals;
  private final LeaseStatusHistoryRepository statusHistory;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final TenantRepository tenants;
  private final OwnerRepository owners;
  private final RentInvoiceRepository invoices;
  private final PaymentAllocationRepository allocations;
  private final PaymentRepository payments;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;
  private final AppProperties propertiesConfig;

  public LeaseService(
      LeaseRepository leases,
      LeaseTermRepository terms,
      LeaseRenewalRepository renewals,
      LeaseStatusHistoryRepository statusHistory,
      PropertyRepository properties,
      UnitRepository units,
      TenantRepository tenants,
      OwnerRepository owners,
      RentInvoiceRepository invoices,
      PaymentAllocationRepository allocations,
      PaymentRepository payments,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope,
      AppProperties propertiesConfig) {
    this.leases = leases;
    this.terms = terms;
    this.renewals = renewals;
    this.statusHistory = statusHistory;
    this.properties = properties;
    this.units = units;
    this.tenants = tenants;
    this.owners = owners;
    this.invoices = invoices;
    this.allocations = allocations;
    this.payments = payments;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
    this.propertiesConfig = propertiesConfig;
  }

  private static Map<LeaseStatus, Set<LeaseStatus>> allowedTransitions() {
    Map<LeaseStatus, Set<LeaseStatus>> map = new EnumMap<>(LeaseStatus.class);
    map.put(LeaseStatus.DRAFT, Set.of(LeaseStatus.PENDING, LeaseStatus.ACTIVE, LeaseStatus.CANCELLED));
    map.put(LeaseStatus.PENDING, Set.of(LeaseStatus.DRAFT, LeaseStatus.ACTIVE, LeaseStatus.CANCELLED));
    map.put(
        LeaseStatus.ACTIVE,
        Set.of(LeaseStatus.EXPIRING, LeaseStatus.RENEWED, LeaseStatus.TERMINATED));
    map.put(
        LeaseStatus.EXPIRING,
        Set.of(LeaseStatus.ACTIVE, LeaseStatus.RENEWED, LeaseStatus.TERMINATED));
    map.put(LeaseStatus.RENEWED, Set.of());
    map.put(LeaseStatus.TERMINATED, Set.of());
    map.put(LeaseStatus.CANCELLED, Set.of());
    return Map.copyOf(map);
  }

  /** True when a lease may move from one state to another. Exposed so the rule is testable. */
  public static boolean isAllowed(LeaseStatus from, LeaseStatus to) {
    if (from == null || to == null) return false;
    return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * The lease register.
   *
   * <p>No repository finder combines every filter, so the candidates are narrowed by the store
   * (all live leases) and filtered here, then sorted and paged. The caller's {@link Pageable} order
   * is applied to the sorted result, so the page the caller asked for is the page they get.</p>
   */
  @Transactional(readOnly = true)
  public Page<LeaseDtos.LeaseSummary> search(
      UUID propertyId,
      UUID unitId,
      UUID tenantId,
      String status,
      LocalDate from,
      LocalDate to,
      Integer expiringWithinDays,
      String search,
      Pageable pageable) {

    LocalDate today = LocalDate.now();
    LeaseStatus statusFilter = parseStatus(status);
    LocalDate expiryTo = expiringWithinDays == null ? null : today.plusDays(expiringWithinDays);
    String term = blankToNull(search);

    List<Lease> filtered = new ArrayList<>();
    for (Lease lease : leases.findAllByDeletedAtIsNull()) {
      if (propertyId != null && !propertyId.equals(propertyIdOf(lease))) continue;
      if (unitId != null && !unitId.equals(lease.getUnitId())) continue;
      if (tenantId != null && !tenantId.equals(lease.getTenantId())) continue;
      if (statusFilter != null && statusFilter != lease.getStatus()) continue;
      if (from != null && (lease.getEndDate() == null || lease.getEndDate().isBefore(from))) continue;
      if (to != null && (lease.getStartDate() == null || lease.getStartDate().isAfter(to))) continue;
      if (expiryTo != null && !insideExpiryWindow(lease, today, expiryTo)) continue;
      if (term != null && !matches(lease, term)) continue;
      filtered.add(lease);
    }

    filtered.sort(leaseSort(pageable.getSort()));
    return toPage(filtered, pageable).map(this::toSummary);
  }

  /** Everything the lease detail screen reads. */
  @Transactional(readOnly = true)
  public LeaseDtos.LeaseDetail detail(UUID leaseId) {
    Lease lease = requireLease(leaseId);
    List<LeaseDtos.LeaseTermView> termViews =
        terms.findAllByLease_IdAndDeletedAtIsNullOrderByTermNumberAsc(leaseId).stream()
            .map(LeaseService::toTermView)
            .toList();
    List<LeaseDtos.StatusChangeView> history =
        statusHistory.findAllByLease_IdOrderByChangedAtDesc(leaseId).stream()
            .map(LeaseService::toStatusView)
            .toList();
    List<LeaseDtos.RenewalView> renewalViews =
        renewals.findAllByLease_IdAndDeletedAtIsNull(leaseId).stream()
            .map(LeaseService::toRenewalView)
            .toList();
    return toDetail(lease, termViews, history, renewalViews);
  }

  /**
   * The leases that are still running and end inside the configured window.
   *
   * <p>The window and the urgency threshold are configuration, not constants
   * ({@code app.portfolio.lease-expiry-warning-days} and {@code …urgent-days}), so a lease cannot be
   * called expiring on one screen and not on another. The window is applied in the query; the
   * urgency band is worked out per lease.</p>
   */
  @Transactional(readOnly = true)
  public List<LeaseDtos.ExpiringLease> expiringLeases(Integer withinDays) {
    int window =
        withinDays == null ? propertiesConfig.getPortfolio().getLeaseExpiryWarningDays() : withinDays;
    int urgent = propertiesConfig.getPortfolio().getLeaseExpiryUrgentDays();
    LocalDate today = LocalDate.now();

    return leases
        .findExpiringBetween(
            today, today.plusDays(window), PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "endDate")))
        .getContent()
        .stream()
        .map(lease -> toExpiring(lease, today, urgent))
        .toList();
  }

  /**
   * The lease's money position, derived from the invoices and receipts that point at it.
   *
   * <p>Nothing here is stored on the lease. {@code settled} is the sum of allocations on
   * {@code PAID} receipts, voided invoices excluded; {@code outstanding} is
   * {@code total - credited - settled} rolled up; {@code overdue} is the part past its due date plus
   * the configured grace period.</p>
   */
  @Transactional(readOnly = true)
  public LeaseDtos.LeaseFinance finance(Lease lease) {
    UUID leaseId = lease.getId();
    LocalDate today = LocalDate.now();

    BigDecimal invoiced = MoneyPolicy.zero();
    BigDecimal settled = MoneyPolicy.zero();
    BigDecimal outstanding = MoneyPolicy.zero();
    BigDecimal overdue = MoneyPolicy.zero();
    long invoiceCount = 0;
    long overdueCount = 0;

    for (RentInvoice invoice :
        invoices.findAllByLease_IdAndDeletedAtIsNull(leaseId, Pageable.unpaged()).getContent()) {
      if (invoice.getStatus() == InvoiceStatus.VOID) continue;
      BigDecimal invoiceSettled = settledOf(invoice);
      BigDecimal invoiceOutstanding =
          MoneyPolicy.money(
              MoneyPolicy.money(invoice.getTotalAmount())
                  .subtract(MoneyPolicy.money(invoice.getCreditedAmount()))
                  .subtract(invoiceSettled));
      invoiced = invoiced.add(MoneyPolicy.money(invoice.getTotalAmount()));
      settled = settled.add(invoiceSettled);
      invoiceCount++;
      if (invoiceOutstanding.signum() > 0) {
        outstanding = outstanding.add(invoiceOutstanding);
        if (isOverdue(invoice, invoiceOutstanding, today)) {
          overdue = overdue.add(invoiceOutstanding);
          overdueCount++;
        }
      }
    }

    long receiptCount =
        payments.findAllByLease_IdAndDeletedAtIsNull(leaseId, Pageable.unpaged()).getTotalElements();
    return new LeaseDtos.LeaseFinance(
        invoiced, settled, outstanding, overdue, invoiceCount, overdueCount, receiptCount);
  }

  // ---------------------------------------------------------------------------
  // Drafting and amending
  // ---------------------------------------------------------------------------

  /** Draft a lease: mint its number, resolve the unit and refuse a second running lease on it. */
  @Transactional
  public LeaseDtos.LeaseSummary createLease(LeaseDtos.CreateLeaseRequest request) {
    User actor = accessScope.requireUser();

    if (request.startDate() == null || request.endDate() == null) {
      throw ApiException.badRequest("A lease needs both a start and an end date");
    }
    if (request.endDate().isBefore(request.startDate())) {
      throw rule("LEASE_END_BEFORE_START");
    }

    Unit unit =
        units
            .findByIdAndDeletedAtIsNull(request.unitId())
            .orElseThrow(() -> notFound("UNIT_NOT_FOUND"));

    // One running lease per unit. The partial unique index backs this up, but the refusal has to be
    // a named one before the index fires with its opaque constraint text.
    leases
        .findRunningLeaseForUnit(request.unitId())
        .ifPresent(
            existing -> {
              throw rule("UNIT_ALREADY_LET");
            });

    Property property = resolveProperty(request.propertyId(), unit);
    Tenant tenant =
        tenants
            .findByIdAndDeletedAtIsNull(request.tenantId())
            .orElseThrow(() -> notFound("TENANT_NOT_FOUND"));

    if (request.primaryOwnerId() != null
        && !owners.existsById(request.primaryOwnerId())) {
      throw notFound("OWNER_NOT_FOUND");
    }

    Lease lease = new Lease();
    lease.setLeaseCode(numbers.next(BusinessNumberService.LEASE));
    lease.setProperty(property);
    lease.setUnitId(unit.getId());
    lease.setTenantId(tenant.getId());
    lease.setPrimaryOwnerId(request.primaryOwnerId());
    lease.setStatus(LeaseStatus.DRAFT);
    lease.setStartDate(request.startDate());
    lease.setEndDate(request.endDate());
    lease.setRentAmount(MoneyPolicy.money(request.rentAmount()));
    lease.setRentFrequency(parseFrequency(request.rentFrequency()));
    lease.setDueDay(normaliseDueDay(request.dueDay()));
    lease.setEscalationPercent(
        request.escalationPercent() == null ? null : MoneyPolicy.money(request.escalationPercent()));
    lease.setDepositAmount(
        request.depositAmount() == null ? MoneyPolicy.zero() : MoneyPolicy.money(request.depositAmount()));
    lease.setNoticePeriodDays(
        request.noticePeriodDays() == null ? NOTICE_PERIOD_DEFAULT_DAYS : request.noticePeriodDays());
    lease.setAgreementReference(request.agreementReference());
    lease.setCreatedBy(actor.getId());
    lease.setUpdatedBy(actor.getId());

    Lease saved = leases.save(lease);
    recordStatusChange(saved, null, LeaseStatus.DRAFT, "Lease drafted", actor.getId());

    recordAudit(
        actor,
        AuditActions.LEASE_CREATED,
        "Drafted lease " + saved.getLeaseCode() + " for " + property.getName(),
        "LEASE",
        saved.getId(),
        saved.getLeaseCode());

    return toSummary(saved);
  }

  /** Change a lease's terms. Only the fields that are sent are applied. */
  @Transactional
  public LeaseDtos.LeaseSummary updateLease(UUID leaseId, LeaseDtos.UpdateLeaseRequest request) {
    User actor = accessScope.requireUser();
    Lease lease = requireLease(leaseId);

    if (request.startDate() != null) {
      lease.setStartDate(request.startDate());
    }
    if (request.endDate() != null) {
      lease.setEndDate(request.endDate());
    }
    if (lease.getEndDate() != null
        && lease.getStartDate() != null
        && lease.getEndDate().isBefore(lease.getStartDate())) {
      throw rule("LEASE_END_BEFORE_START");
    }
    if (request.rentAmount() != null) {
      lease.setRentAmount(MoneyPolicy.money(request.rentAmount()));
    }
    if (request.rentFrequency() != null) {
      lease.setRentFrequency(parseFrequency(request.rentFrequency()));
    }
    if (request.dueDay() != null) {
      lease.setDueDay(normaliseDueDay(request.dueDay()));
    }
    if (request.escalationPercent() != null) {
      lease.setEscalationPercent(MoneyPolicy.money(request.escalationPercent()));
    }
    if (request.depositAmount() != null) {
      lease.setDepositAmount(MoneyPolicy.money(request.depositAmount()));
    }
    if (request.noticePeriodDays() != null) {
      lease.setNoticePeriodDays(request.noticePeriodDays());
    }
    if (request.agreementReference() != null) {
      lease.setAgreementReference(request.agreementReference());
    }
    if (request.primaryOwnerId() != null) {
      if (!owners.existsById(request.primaryOwnerId())) {
        throw notFound("OWNER_NOT_FOUND");
      }
      lease.setPrimaryOwnerId(request.primaryOwnerId());
    }
    lease.setUpdatedBy(actor.getId());

    Lease saved = leases.save(lease);
    recordAudit(
        actor,
        AuditActions.UPDATE,
        "Updated lease " + saved.getLeaseCode(),
        "LEASE",
        saved.getId(),
        saved.getLeaseCode());
    return toSummary(saved);
  }

  // ---------------------------------------------------------------------------
  // The lifecycle
  // ---------------------------------------------------------------------------

  /** Move a lease to the named state, refusing an illegal move. */
  @Transactional
  public LeaseDtos.LeaseSummary changeStatus(UUID leaseId, LeaseDtos.ChangeStatusRequest request) {
    accessScope.requireUser();
    Lease lease = requireLease(leaseId);
    if (request.status() == null || request.status().isBlank()) {
      throw ApiException.badRequest("A target status is required");
    }
    LeaseStatus target = parseStatusRequired(request.status());
    return applyStatus(lease, target, request.reason());
  }

  /** Activate a draft or pending lease, refused if the unit already runs a different lease. */
  @Transactional
  public LeaseDtos.LeaseSummary activate(UUID leaseId, LeaseDtos.ChangeStatusRequest request) {
    accessScope.requireUser();
    Lease lease = requireLease(leaseId);
    return applyStatus(lease, LeaseStatus.ACTIVE, request == null ? null : request.reason());
  }

  /** End a lease with a recorded reason. */
  @Transactional
  public LeaseDtos.LeaseSummary terminateLease(UUID leaseId, LeaseDtos.TerminateRequest request) {
    accessScope.requireUser();
    Lease lease = requireLease(leaseId);
    return applyStatus(lease, LeaseStatus.TERMINATED, request.reason());
  }

  private LeaseDtos.LeaseSummary applyStatus(Lease lease, LeaseStatus target, String reason) {
    User actor = accessScope.requireUser();
    LeaseStatus from = lease.getStatus();
    if (!isAllowed(from, target)) {
      throw rule("LEASE_TRANSITION_NOT_ALLOWED");
    }
    if (target == LeaseStatus.TERMINATED && (reason == null || reason.isBlank())) {
      throw ApiException.badRequest("A termination reason is required");
    }
    if (target.isRunning()) {
      // The unit must not already be let to a different running lease (uq_leases_one_running_per_unit).
      leases
          .findRunningLeaseForUnit(lease.getUnitId())
          .filter(existing -> !Objects.equals(existing.getId(), lease.getId()))
          .ifPresent(
              existing -> {
                throw rule("UNIT_ALREADY_LET");
              });
    }

    LocalDateTime now = LocalDateTime.now();
    lease.setStatus(target);
    if (target.isRunning() && lease.getActivatedAt() == null) {
      lease.setActivatedAt(now);
    }
    if (target == LeaseStatus.TERMINATED) {
      lease.setTerminatedAt(now);
      lease.setTerminationReason(reason);
    }
    lease.setUpdatedBy(actor.getId());
    Lease saved = leases.save(lease);

    recordStatusChange(saved, from, target, reason, actor.getId());
    recordAudit(
        actor,
        AuditActions.LEASE_STATUS_CHANGED,
        "Lease " + saved.getLeaseCode() + " moved from " + name(from) + " to " + name(target),
        "LEASE",
        saved.getId(),
        saved.getLeaseCode());
    return toSummary(saved);
  }

  // ---------------------------------------------------------------------------
  // The rent schedule
  // ---------------------------------------------------------------------------

  /**
   * Add a step to a lease's rent schedule.
   *
   * <p>Refused when it overlaps a term already on the lease - "the rent in force on 1 March" has to
   * be single-valued, and two terms covering one day would give two answers. The check spans rows,
   * so it is here rather than in the schema.</p>
   */
  @Transactional
  public LeaseDtos.LeaseTermView createTerm(UUID leaseId, LeaseDtos.CreateTermRequest request) {
    User actor = accessScope.requireUser();
    Lease lease = requireLease(leaseId);

    if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
      throw ApiException.badRequest("A term's end cannot fall before its start");
    }
    List<LeaseTerm> existing = terms.findAllByLease_IdAndDeletedAtIsNullOrderByTermNumberAsc(leaseId);
    for (LeaseTerm term : existing) {
      if (overlaps(
          request.effectiveFrom(),
          request.effectiveTo(),
          term.getEffectiveFrom(),
          term.getEffectiveTo())) {
        throw rule("LEASE_TERM_OVERLAP");
      }
    }

    LeaseTerm term = new LeaseTerm();
    term.setLease(lease);
    term.setTermNumber(existing.size() + 1);
    term.setEffectiveFrom(request.effectiveFrom());
    term.setEffectiveTo(request.effectiveTo());
    term.setRentAmount(MoneyPolicy.money(request.rentAmount()));
    term.setEscalationPercent(
        request.escalationPercent() == null ? null : MoneyPolicy.money(request.escalationPercent()));
    term.setNotes(request.notes());
    term.setCreatedBy(actor.getId());
    term.setUpdatedBy(actor.getId());
    LeaseTerm saved = terms.save(term);

    recordAudit(
        actor,
        AuditActions.CREATE,
        "Added rent term " + saved.getTermNumber() + " of " + saved.getRentAmount()
            + " to lease " + lease.getLeaseCode(),
        "LEASE_TERM",
        saved.getId(),
        lease.getLeaseCode());
    return toTermView(saved);
  }

  // ---------------------------------------------------------------------------
  // Renewals
  // ---------------------------------------------------------------------------

  /** Open a renewal conversation for a lease. */
  @Transactional
  public LeaseDtos.RenewalView proposeRenewal(UUID leaseId, LeaseDtos.ProposeRenewalRequest request) {
    User actor = accessScope.requireUser();
    Lease lease = requireLease(leaseId);

    LeaseRenewal renewal = new LeaseRenewal();
    renewal.setLease(lease);
    renewal.setStatus(LeaseRenewalStatus.PENDING);
    renewal.setProposedAt(LocalDateTime.now());
    renewal.setProposedRent(
        request.proposedRent() == null ? null : MoneyPolicy.money(request.proposedRent()));
    renewal.setProposedStartDate(request.proposedStartDate());
    renewal.setProposedEndDate(request.proposedEndDate());
    renewal.setCreatedBy(actor.getId());
    renewal.setUpdatedBy(actor.getId());
    LeaseRenewal saved = renewals.save(renewal);

    recordAudit(
        actor,
        AuditActions.CREATE,
        "Opened a renewal for lease " + lease.getLeaseCode(),
        "LEASE_RENEWAL",
        saved.getId(),
        lease.getLeaseCode());
    return toRenewalView(saved);
  }

  /**
   * Put a renewal offer to the tenant.
   *
   * <p>Only a lease that is still running can be offered a renewal - a lease that has already ended,
   * been renewed or been cancelled has nothing to offer - and the refusal is the named
   * {@code LEASE_RENEWAL_NOT_OFFERED}.</p>
   */
  @Transactional
  public LeaseDtos.RenewalView offerRenewal(UUID renewalId, LeaseDtos.OfferRenewalRequest request) {
    User actor = accessScope.requireUser();
    LeaseRenewal renewal = requireRenewal(renewalId);
    Lease lease = renewal.getLease();
    if (lease == null || lease.getStatus() == null || !lease.getStatus().isRunning()) {
      throw rule("LEASE_RENEWAL_NOT_OFFERED");
    }
    if (!renewal.getStatus().isOpen()) {
      throw rule("LEASE_RENEWAL_NOT_OFFERED");
    }

    renewal.setStatus(LeaseRenewalStatus.OFFERED);
    renewal.setProposedAt(LocalDateTime.now());
    if (request.proposedRent() != null) {
      renewal.setProposedRent(MoneyPolicy.money(request.proposedRent()));
    }
    if (request.proposedStartDate() != null) {
      renewal.setProposedStartDate(request.proposedStartDate());
    }
    if (request.proposedEndDate() != null) {
      renewal.setProposedEndDate(request.proposedEndDate());
    }
    renewal.setUpdatedBy(actor.getId());
    LeaseRenewal saved = renewals.save(renewal);

    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Offered a renewal on lease " + lease.getLeaseCode(),
        "LEASE_RENEWAL",
        saved.getId(),
        lease.getLeaseCode());
    return toRenewalView(saved);
  }

  /**
   * Accept a renewal: draft the new lease and move the old one to {@code RENEWED}.
   *
   * <p>This is the one place a renewal becomes real. The new lease points back at the one it
   * replaced through {@code renewal_of_lease_id}, which is what makes a tenancy reconstructable
   * across its renewals.</p>
   */
  @Transactional
  public LeaseDtos.RenewalView acceptRenewal(UUID renewalId, LeaseDtos.DecideRenewalRequest request) {
    User actor = accessScope.requireUser();
    LeaseRenewal renewal = requireRenewal(renewalId);
    if (renewal.getStatus() != LeaseRenewalStatus.OFFERED) {
      throw rule("LEASE_RENEWAL_NOT_OFFERED");
    }
    Lease previous = renewal.getLease();
    if (previous == null) {
      throw rule("LEASE_RENEWAL_NOT_OFFERED");
    }

    LocalDate start =
        firstNonNull(
            request.startDate(),
            renewal.getProposedStartDate(),
            previous.getEndDate() == null ? LocalDate.now() : previous.getEndDate().plusDays(1));
    LocalDate end =
        firstNonNull(
            request.endDate(),
            renewal.getProposedEndDate(),
            start.plusYears(1));
    if (end.isBefore(start)) {
      throw rule("LEASE_END_BEFORE_START");
    }
    BigDecimal rent =
        firstNonNull(request.agreedRent(), renewal.getProposedRent(), previous.getRentAmount());

    Lease next = new Lease();
    next.setLeaseCode(numbers.next(BusinessNumberService.LEASE));
    next.setProperty(previous.getProperty());
    next.setUnitId(previous.getUnitId());
    next.setTenantId(previous.getTenantId());
    next.setPrimaryOwnerId(previous.getPrimaryOwnerId());
    next.setStatus(LeaseStatus.DRAFT);
    next.setStartDate(start);
    next.setEndDate(end);
    next.setRentAmount(MoneyPolicy.money(rent));
    next.setRentFrequency(previous.getRentFrequency());
    next.setDueDay(previous.getDueDay());
    next.setEscalationPercent(previous.getEscalationPercent());
    next.setDepositAmount(previous.getDepositAmount());
    next.setNoticePeriodDays(previous.getNoticePeriodDays());
    next.setRenewalOfLease(previous);
    next.setCreatedBy(actor.getId());
    next.setUpdatedBy(actor.getId());
    Lease newLease = leases.save(next);
    recordStatusChange(newLease, null, LeaseStatus.DRAFT, "Renewal of " + previous.getLeaseCode(), actor.getId());

    // Supersede the lease that was renewed. Its end date and history are untouched.
    applyStatusTo(previous, LeaseStatus.RENEWED, "Renewed by " + newLease.getLeaseCode(), actor);

    renewal.setStatus(LeaseRenewalStatus.ACCEPTED);
    renewal.setRenewedLease(newLease);
    renewal.setDecidedAt(LocalDateTime.now());
    renewal.setDecidedBy(actor.getId());
    renewal.setDecisionNotes(request.notes());
    renewal.setUpdatedBy(actor.getId());
    LeaseRenewal saved = renewals.save(renewal);

    recordAudit(
        actor,
        AuditActions.LEASE_RENEWED,
        "Renewed lease " + previous.getLeaseCode() + " as " + newLease.getLeaseCode(),
        "LEASE_RENEWAL",
        saved.getId(),
        previous.getLeaseCode());
    return toRenewalView(saved);
  }

  /** Decline a renewal offer, with the reason recorded against the decision. */
  @Transactional
  public LeaseDtos.RenewalView declineRenewal(UUID renewalId, LeaseDtos.DeclineRenewalRequest request) {
    User actor = accessScope.requireUser();
    LeaseRenewal renewal = requireRenewal(renewalId);
    if (renewal.getStatus() != LeaseRenewalStatus.OFFERED) {
      throw rule("LEASE_RENEWAL_NOT_OFFERED");
    }
    renewal.setStatus(LeaseRenewalStatus.DECLINED);
    renewal.setDecidedAt(LocalDateTime.now());
    renewal.setDecidedBy(actor.getId());
    renewal.setDecisionNotes(request.notes());
    renewal.setUpdatedBy(actor.getId());
    LeaseRenewal saved = renewals.save(renewal);

    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Declined the renewal on lease "
            + (renewal.getLease() == null ? null : renewal.getLease().getLeaseCode()),
        "LEASE_RENEWAL",
        saved.getId(),
        renewal.getLease() == null ? null : renewal.getLease().getLeaseCode());
    return toRenewalView(saved);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * The transition, without re-requiring the actor.
   *
   * <p>Split out from {@link #applyStatus(Lease, LeaseStatus, String)} so a renewal can supersede the
   * lease it renews while the actor who accepted it is already in hand.</p>
   */
  private void applyStatusTo(Lease lease, LeaseStatus target, String reason, User actor) {
    LeaseStatus from = lease.getStatus();
    if (!isAllowed(from, target)) {
      throw rule("LEASE_TRANSITION_NOT_ALLOWED");
    }
    lease.setStatus(target);
    if (target.isRunning() && lease.getActivatedAt() == null) {
      lease.setActivatedAt(LocalDateTime.now());
    }
    lease.setUpdatedBy(actor.getId());
    Lease saved = leases.save(lease);
    recordStatusChange(saved, from, target, reason, actor.getId());
    recordAudit(
        actor,
        AuditActions.LEASE_STATUS_CHANGED,
        "Lease " + saved.getLeaseCode() + " moved from " + name(from) + " to " + name(target),
        "LEASE",
        saved.getId(),
        saved.getLeaseCode());
  }

  private Lease requireLease(UUID leaseId) {
    return leases
        .findByIdAndDeletedAtIsNull(leaseId)
        .orElseThrow(() -> notFound("LEASE_NOT_FOUND"));
  }

  private LeaseRenewal requireRenewal(UUID renewalId) {
    return renewals
        .findByIdAndDeletedAtIsNull(renewalId)
        .orElseThrow(() -> ApiException.notFound("We could not find that renewal"));
  }

  private Property resolveProperty(UUID propertyId, Unit unit) {
    if (propertyId != null) {
      return properties
          .findByIdAndDeletedAtIsNull(propertyId)
          .orElseThrow(() -> notFound("PROPERTY_NOT_FOUND"));
    }
    if (unit.getProperty() == null) {
      throw notFound("PROPERTY_NOT_FOUND");
    }
    return unit.getProperty();
  }

  /** The sum of allocations on a non-voided {@code PAID} receipt. Zero for a void invoice. */
  private BigDecimal settledOf(RentInvoice invoice) {
    if (invoice.getStatus() == InvoiceStatus.VOID) return MoneyPolicy.zero();
    BigDecimal sum = BigDecimal.ZERO;
    for (PaymentAllocation allocation : allocations.findAllByInvoiceIdFetchPayment(invoice.getId())) {
      Payment payment = allocation.getPayment();
      if (payment == null || payment.getStatus() != PaymentStatus.PAID) continue;
      sum = sum.add(MoneyPolicy.money(allocation.getAmount()));
    }
    return MoneyPolicy.money(sum);
  }

  /** {@code due_date + grace < today}, on a collectable invoice that still owes something. */
  private boolean isOverdue(RentInvoice invoice, BigDecimal outstanding, LocalDate asOf) {
    if (invoice.getStatus() != InvoiceStatus.ISSUED) return false;
    if (outstanding == null || outstanding.signum() <= 0) return false;
    if (invoice.getDueDate() == null) return false;
    int grace = propertiesConfig.getPortfolio().getRentOverdueGraceDays();
    return invoice.getDueDate().plusDays(grace).isBefore(asOf);
  }

  private LeaseDtos.LeaseDetail toDetail(
      Lease lease,
      List<LeaseDtos.LeaseTermView> termViews,
      List<LeaseDtos.StatusChangeView> history,
      List<LeaseDtos.RenewalView> renewalViews) {
    Property property = lease.getProperty();
    return new LeaseDtos.LeaseDetail(
        lease.getId(),
        lease.getLeaseCode(),
        name(lease.getStatus()),
        property == null ? null : property.getId(),
        property == null ? null : property.getName(),
        lease.getUnitId(),
        lease.getTenantId(),
        tenantName(lease.getTenantId()),
        lease.getPrimaryOwnerId(),
        lease.getStartDate(),
        lease.getEndDate(),
        MoneyPolicy.money(lease.getRentAmount()),
        lease.getRentFrequency() == null ? null : lease.getRentFrequency().name(),
        lease.getDueDay(),
        lease.getEscalationPercent() == null ? null : MoneyPolicy.money(lease.getEscalationPercent()),
        MoneyPolicy.money(lease.getDepositAmount()),
        lease.getDepositReceivedAt(),
        lease.getDepositRefundedAmount() == null ? null : MoneyPolicy.money(lease.getDepositRefundedAmount()),
        lease.getDepositRefundedAt(),
        lease.getNoticePeriodDays(),
        lease.getAgreementReference(),
        lease.getSignedAt(),
        lease.getActivatedAt(),
        lease.getTerminatedAt(),
        lease.getTerminationReason(),
        lease.getRenewalOfLease() == null ? null : lease.getRenewalOfLease().getId(),
        lease.getCreatedAt(),
        lease.getUpdatedAt(),
        termViews,
        history,
        renewalViews,
        finance(lease));
  }

  private LeaseDtos.LeaseSummary toSummary(Lease lease) {
    Property property = lease.getProperty();
    return new LeaseDtos.LeaseSummary(
        lease.getId(),
        lease.getLeaseCode(),
        name(lease.getStatus()),
        property == null ? null : property.getId(),
        property == null ? null : property.getName(),
        lease.getUnitId(),
        lease.getTenantId(),
        tenantName(lease.getTenantId()),
        lease.getStartDate(),
        lease.getEndDate(),
        MoneyPolicy.money(lease.getRentAmount()),
        lease.getRentFrequency() == null ? null : lease.getRentFrequency().name(),
        lease.getDueDay(),
        MoneyPolicy.money(lease.getDepositAmount()),
        lease.getCreatedAt());
  }

  private LeaseDtos.ExpiringLease toExpiring(Lease lease, LocalDate today, int urgentDays) {
    Property property = lease.getProperty();
    long daysUntilEnd = ChronoUnit.DAYS.between(today, lease.getEndDate());
    return new LeaseDtos.ExpiringLease(
        lease.getId(),
        lease.getLeaseCode(),
        name(lease.getStatus()),
        property == null ? null : property.getId(),
        property == null ? null : property.getName(),
        lease.getUnitId(),
        lease.getTenantId(),
        tenantName(lease.getTenantId()),
        lease.getEndDate(),
        daysUntilEnd,
        daysUntilEnd <= urgentDays ? "URGENT" : "WARNING",
        MoneyPolicy.money(lease.getRentAmount()));
  }

  private static LeaseDtos.LeaseTermView toTermView(LeaseTerm term) {
    return new LeaseDtos.LeaseTermView(
        term.getId(),
        term.getTermNumber(),
        term.getEffectiveFrom(),
        term.getEffectiveTo(),
        MoneyPolicy.money(term.getRentAmount()),
        term.getEscalationPercent() == null ? null : MoneyPolicy.money(term.getEscalationPercent()),
        term.getNotes());
  }

  private static LeaseDtos.StatusChangeView toStatusView(LeaseStatusHistory history) {
    return new LeaseDtos.StatusChangeView(
        history.getId(),
        name(history.getFromStatus()),
        name(history.getToStatus()),
        history.getReason(),
        history.getChangedBy(),
        history.getChangedAt());
  }

  private static LeaseDtos.RenewalView toRenewalView(LeaseRenewal renewal) {
    return new LeaseDtos.RenewalView(
        renewal.getId(),
        renewal.getLease() == null ? null : renewal.getLease().getId(),
        renewal.getRenewedLease() == null ? null : renewal.getRenewedLease().getId(),
        renewal.getStatus() == null ? null : renewal.getStatus().name(),
        renewal.getProposedAt(),
        renewal.getProposedRent() == null ? null : MoneyPolicy.money(renewal.getProposedRent()),
        renewal.getProposedStartDate(),
        renewal.getProposedEndDate(),
        renewal.getDecidedAt(),
        renewal.getDecidedBy(),
        renewal.getDecisionNotes());
  }

  private void recordStatusChange(
      Lease lease, LeaseStatus from, LeaseStatus to, String reason, UUID actorId) {
    LeaseStatusHistory history = new LeaseStatusHistory();
    history.setLease(lease);
    history.setFromStatus(from);
    history.setToStatus(to);
    history.setReason(reason);
    history.setChangedBy(actorId);
    history.setChangedAt(LocalDateTime.now());
    statusHistory.save(history);
  }

  private void recordAudit(
      User actor, String action, String description, String entityType, UUID entityId, String label) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.LEASE,
        action,
        description,
        entityType,
        entityId,
        label);
  }

  private String tenantName(UUID tenantId) {
    if (tenantId == null) return null;
    return tenants.findByIdAndDeletedAtIsNull(tenantId).map(Tenant::getDisplayName).orElse(null);
  }

  private static UUID propertyIdOf(Lease lease) {
    return lease.getProperty() == null ? null : lease.getProperty().getId();
  }

  private static boolean insideExpiryWindow(Lease lease, LocalDate today, LocalDate expiryTo) {
    if (lease.getStatus() == null || !lease.getStatus().isRunning()) return false;
    if (lease.getEndDate() == null) return false;
    return !lease.getEndDate().isBefore(today) && !lease.getEndDate().isAfter(expiryTo);
  }

  private static boolean matches(Lease lease, String term) {
    String needle = term.toLowerCase(Locale.ROOT);
    return contains(lease.getLeaseCode(), needle)
        || contains(lease.getAgreementReference(), needle);
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  /** True when two date intervals share a day; a null end is open-ended. */
  static boolean overlaps(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
    if (aFrom == null || bFrom == null) return false;
    LocalDate aEnd = aTo == null ? LocalDate.MAX : aTo;
    LocalDate bEnd = bTo == null ? LocalDate.MAX : bTo;
    return !aFrom.isAfter(bEnd) && !bFrom.isAfter(aEnd);
  }

  private static String name(LeaseStatus status) {
    return status == null ? null : status.name();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static LeaseStatus parseStatus(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LeaseStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a lease status");
    }
  }

  private static LeaseStatus parseStatusRequired(String value) {
    LeaseStatus status = parseStatus(value);
    if (status == null) throw ApiException.badRequest("A target status is required");
    return status;
  }

  private RentFrequency parseFrequency(String value) {
    if (value == null || value.isBlank()) return RentFrequency.MONTHLY;
    try {
      return RentFrequency.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a rent frequency");
    }
  }

  private int normaliseDueDay(Integer dueDay) {
    if (dueDay == null) return propertiesConfig.getPortfolio().getRentDueDay();
    if (dueDay < 1 || dueDay > 28) {
      throw ApiException.badRequest("A due day must be between 1 and 28");
    }
    return dueDay;
  }

  @SafeVarargs
  private static <T> T firstNonNull(T... values) {
    for (T value : values) {
      if (value != null) return value;
    }
    return null;
  }

  /** The order the caller asked for, applied to the in-memory candidate list. */
  private static Comparator<Lease> leaseSort(Sort sort) {
    Comparator<Lease> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Lease> next =
          switch (order.getProperty()) {
            case "leaseCode" ->
                Comparator.comparing(
                    Lease::getLeaseCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "startDate" ->
                Comparator.comparing(Lease::getStartDate, Comparator.nullsLast(LocalDate::compareTo));
            case "endDate" ->
                Comparator.comparing(Lease::getEndDate, Comparator.nullsLast(LocalDate::compareTo));
            case "rentAmount" ->
                Comparator.comparing(Lease::getRentAmount, Comparator.nullsLast(BigDecimal::compareTo));
            case "status" ->
                Comparator.comparing(
                    (Lease lease) -> name(lease.getStatus()),
                    Comparator.nullsLast(String::compareTo));
            case "createdAt" ->
                Comparator.comparing(
                    Lease::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo));
            default -> null;
          };
      if (next == null) continue;
      if (order.isDescending()) next = next.reversed();
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    return comparator == null
        ? Comparator.comparing(Lease::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
            .reversed()
        : comparator;
  }

  private static <T> Page<T> toPage(List<T> sorted, Pageable pageable) {
    int total = sorted.size();
    long offset = pageable.getOffset();
    if (offset >= total) return new PageImpl<>(List.of(), pageable, total);
    int start = (int) offset;
    int end = Math.min(start + pageable.getPageSize(), total);
    return new PageImpl<>(new ArrayList<>(sorted.subList(start, end)), pageable, total);
  }

  private static ApiException rule(String code) {
    return ApiException.rule(code, ErrorMessages.friendly(code));
  }

  private static ApiException notFound(String code) {
    return new ApiException(HttpStatus.NOT_FOUND, code, ErrorMessages.friendly(code));
  }
}
