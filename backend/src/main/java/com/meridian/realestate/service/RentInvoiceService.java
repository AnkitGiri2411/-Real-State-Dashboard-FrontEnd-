package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.InvoiceStatus;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.PaymentAllocation;
import com.meridian.realestate.domain.PaymentStatus;
import com.meridian.realestate.domain.RentInvoice;
import com.meridian.realestate.domain.RentInvoiceLine;
import com.meridian.realestate.domain.RentInvoiceLineType;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.RentInvoiceDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.PaymentAllocationRepository;
import com.meridian.realestate.repository.RentInvoiceLineRepository;
import com.meridian.realestate.repository.RentInvoiceRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rent invoices: the document that demands the rent, and the settlement derived from it.
 *
 * <h2>The one rule this class exists to protect</h2>
 * <p>Whether an invoice is <em>paid</em> is not stored anywhere. There is no {@code amount_paid}
 * column and no {@code PAID} status - the V13 schema refuses both, because a stored settlement is a
 * value that can silently disagree with the allocations it summarises. Instead:</p>
 *
 * <pre>
 *   settled     = SUM(allocation.amount) over non-voided, PAID payments
 *   outstanding = total_amount - credited_amount - settled
 *   overdue     = outstanding &gt; 0 AND due_date + grace &lt; today
 * </pre>
 *
 * <p>That derivation lives in exactly one place here, {@link #settlementOf(RentInvoice, LocalDate)},
 * so the invoice detail screen, the lease's money position and the collections board cannot disagree
 * about what has been paid.</p>
 *
 * <h2>The rules the schema does not enforce</h2>
 * <p>{@code SUM(rent_invoice_lines.line_total)} must equal {@code rent_invoices.subtotal} - the check
 * spans rows, so it is here, inside the issuing transaction. And a {@code VOID} invoice's allocations
 * must not count toward settlement, which the derivation excludes by status rather than by deleting
 * the allocation rows.</p>
 *
 * <p><b>A line may be negative.</b> A goodwill credit is modelled as an {@code ADJUSTMENT} line with
 * a negative unit amount and a negative line total, and nothing here assumes positivity. Only the
 * invoice's own {@code subtotal} is constrained to be non-negative, by
 * {@code ck_rent_invoices_subtotal_non_negative}.</p>
 */
@Service
public class RentInvoiceService {

  /**
   * The audit module for rent-invoice events.
   *
   * <p>Written as the product's own module name rather than as an {@link AuditActions} constant
   * because {@code AuditActions} declares no {@code RENT} module - and the doctrine there is that the
   * trail's module must equal the permission module ({@code RENT_VIEW}, {@code RENT_GENERATE}), which
   * {@code V2} spells {@code RENT}. Filing invoices under {@code PAYMENTS} would make "show me the
   * payments module" report rent invoices, so the value is kept honest here.</p>
   */
  private static final String AUDIT_MODULE = "RENT";

  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  private final RentInvoiceRepository invoices;
  private final RentInvoiceLineRepository lines;
  private final PaymentAllocationRepository allocations;
  private final LeaseRepository leases;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;
  private final AppProperties propertiesConfig;

  public RentInvoiceService(
      RentInvoiceRepository invoices,
      RentInvoiceLineRepository lines,
      PaymentAllocationRepository allocations,
      LeaseRepository leases,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope,
      AppProperties propertiesConfig) {
    this.invoices = invoices;
    this.lines = lines;
    this.allocations = allocations;
    this.leases = leases;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
    this.propertiesConfig = propertiesConfig;
  }

  // ---------------------------------------------------------------------------
  // Issuing
  // ---------------------------------------------------------------------------

  /**
   * Raise an invoice for a lease and a period.
   *
   * <p>The number is minted here, the lines are built and their tax computed server-side, the lines
   * are reconciled against the subtotal, and the duplicate period is refused. Nothing about whether
   * the invoice is paid is written - there is nowhere to write it.</p>
   */
  @Transactional
  public RentInvoiceDtos.RentInvoiceDetail issue(
      UUID leaseId, RentInvoiceDtos.IssueInvoiceRequest request) {
    User actor = accessScope.requireUser();

    Lease lease =
        leases.findByIdAndDeletedAtIsNull(leaseId).orElseThrow(() -> notFound("LEASE_NOT_FOUND"));

    if (request.periodStart() == null || request.periodEnd() == null) {
      throw ApiException.badRequest("An invoice needs a period");
    }
    if (request.periodEnd().isBefore(request.periodStart())) {
      throw ApiException.badRequest("The invoice period end cannot fall before its start");
    }
    if (request.lines() == null || request.lines().isEmpty()) {
      throw ApiException.badRequest("An invoice needs at least one line");
    }

    // Build the lines and derive the subtotal and tax from them. The tax on a credit line is zero:
    // a line's tax_amount is constrained non-negative by the schema.
    List<RentInvoiceLine> builtLines = new ArrayList<>();
    BigDecimal derivedSubtotal = MoneyPolicy.zero();
    BigDecimal derivedTax = MoneyPolicy.zero();
    int nextOrder = 0;
    for (RentInvoiceDtos.InvoiceLineRequest lineRequest : request.lines()) {
      BigDecimal quantity =
          lineRequest.quantity() == null ? BigDecimal.ONE : MoneyPolicy.money(lineRequest.quantity());
      if (quantity.signum() <= 0) {
        throw ApiException.badRequest("A line quantity must be greater than zero");
      }
      BigDecimal unitAmount = MoneyPolicy.money(lineRequest.unitAmount());
      BigDecimal lineTotal = MoneyPolicy.money(quantity.multiply(unitAmount));
      BigDecimal rate =
          lineRequest.taxRatePercent() == null
              ? MoneyPolicy.zero()
              : MoneyPolicy.money(lineRequest.taxRatePercent());
      BigDecimal lineTax =
          lineTotal.signum() > 0
              ? MoneyPolicy.money(lineTotal.multiply(rate).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP))
              : MoneyPolicy.zero();

      RentInvoiceLine line = new RentInvoiceLine();
      line.setLineType(parseLineType(lineRequest.lineType()));
      line.setDescription(lineRequest.description());
      line.setQuantity(quantity);
      line.setUnitAmount(unitAmount);
      line.setLineTotal(lineTotal);
      line.setTaxRatePercent(rate);
      line.setTaxAmount(lineTax);
      line.setDisplayOrder(lineRequest.displayOrder() == null ? nextOrder++ : lineRequest.displayOrder());
      line.setCreatedBy(actor.getId());
      line.setUpdatedBy(actor.getId());
      builtLines.add(line);

      derivedSubtotal = derivedSubtotal.add(lineTotal);
      derivedTax = derivedTax.add(lineTax);
    }

    // Reconcile. When the caller states a subtotal it must equal the sum of the lines; otherwise the
    // lines are the authority. Either way the stored subtotal equals SUM(line_total).
    BigDecimal subtotal =
        request.subtotal() == null ? derivedSubtotal : MoneyPolicy.money(request.subtotal());
    if (subtotal.compareTo(derivedSubtotal) != 0) {
      throw rule("INVOICE_LINES_DO_NOT_RECONCILE");
    }
    if (subtotal.signum() < 0) {
      throw ApiException.badRequest("An invoice subtotal cannot be negative");
    }
    BigDecimal total = MoneyPolicy.money(subtotal.add(derivedTax));

    // One invoice per lease per period - the partial unique index backs this up.
    for (RentInvoice existing :
        invoices.findAllByLease_IdAndDeletedAtIsNull(leaseId, Pageable.unpaged()).getContent()) {
      if (request.periodStart().equals(existing.getPeriodStart())
          && request.periodEnd().equals(existing.getPeriodEnd())) {
        throw rule("INVOICE_PERIOD_ALREADY_INVOICED");
      }
    }

    LocalDate dueDate =
        request.dueDate() == null
            ? deriveDueDate(request.periodStart(), lease.getDueDay())
            : request.dueDate();

    RentInvoice invoice = new RentInvoice();
    invoice.setInvoiceNumber(numbers.next(BusinessNumberService.INVOICE));
    invoice.setLease(lease);
    invoice.setTenantId(lease.getTenantId());
    invoice.setProperty(lease.getProperty());
    invoice.setUnitId(lease.getUnitId());
    invoice.setPeriodStart(request.periodStart());
    invoice.setPeriodEnd(request.periodEnd());
    invoice.setDueDate(dueDate);
    invoice.setSubtotal(subtotal);
    invoice.setTaxAmount(derivedTax);
    invoice.setTotalAmount(total);
    invoice.setCreditedAmount(MoneyPolicy.zero());
    invoice.setStatus(InvoiceStatus.ISSUED);
    invoice.setIssuedAt(LocalDateTime.now());
    invoice.setNotes(request.notes());
    invoice.setCreatedBy(actor.getId());
    invoice.setUpdatedBy(actor.getId());
    RentInvoice saved = invoices.save(invoice);

    for (RentInvoiceLine line : builtLines) {
      line.setInvoice(saved);
      lines.save(line);
    }

    recordAudit(
        actor,
        AuditActions.CREATE,
        "Issued invoice " + saved.getInvoiceNumber() + " for lease " + lease.getLeaseCode()
            + " (" + subtotal + " + tax " + derivedTax + ")",
        "RENT_INVOICE",
        saved.getId(),
        saved.getInvoiceNumber());
    return toDetail(saved);
  }

  /** Void an issued invoice. Its allocations stop counting toward settlement. */
  @Transactional
  public RentInvoiceDtos.RentInvoiceDetail voidInvoice(UUID invoiceId, String reason) {
    User actor = accessScope.requireUser();
    RentInvoice invoice = requireInvoice(invoiceId);
    if (invoice.getStatus() == InvoiceStatus.VOID) {
      throw rule("INVOICE_ALREADY_VOID");
    }
    if (reason == null || reason.isBlank()) {
      throw ApiException.badRequest("A reason is required to void an invoice");
    }

    invoice.setStatus(InvoiceStatus.VOID);
    invoice.setVoidedAt(LocalDateTime.now());
    invoice.setVoidedBy(actor.getId());
    invoice.setVoidReason(reason);
    invoice.setUpdatedBy(actor.getId());
    RentInvoice saved = invoices.save(invoice);

    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Voided invoice " + saved.getInvoiceNumber(),
        "RENT_INVOICE",
        saved.getId(),
        saved.getInvoiceNumber());
    return toDetail(saved);
  }

  /** Write an issued invoice off as uncollectable, with the reason recorded. */
  @Transactional
  public RentInvoiceDtos.RentInvoiceDetail writeOff(UUID invoiceId, String reason) {
    User actor = accessScope.requireUser();
    RentInvoice invoice = requireInvoice(invoiceId);
    if (invoice.getStatus() == InvoiceStatus.VOID) {
      throw rule("INVOICE_ALREADY_VOID");
    }
    if (invoice.getStatus() != InvoiceStatus.ISSUED) {
      throw ApiException.badRequest("Only an issued invoice can be written off");
    }
    if (reason == null || reason.isBlank()) {
      throw ApiException.badRequest("A reason is required to write an invoice off");
    }

    invoice.setStatus(InvoiceStatus.WRITTEN_OFF);
    invoice.setWrittenOffAt(LocalDateTime.now());
    invoice.setWrittenOffBy(actor.getId());
    invoice.setWriteOffReason(reason);
    invoice.setUpdatedBy(actor.getId());
    RentInvoice saved = invoices.save(invoice);

    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Wrote off invoice " + saved.getInvoiceNumber(),
        "RENT_INVOICE",
        saved.getId(),
        saved.getInvoiceNumber());
    return toDetail(saved);
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** The invoice register. Filters are applied over the live invoices, then sorted and paged. */
  @Transactional(readOnly = true)
  public Page<RentInvoiceDtos.RentInvoiceSummary> search(
      UUID leaseId,
      UUID tenantId,
      String status,
      LocalDate from,
      LocalDate to,
      Boolean overdueOnly,
      String search,
      Pageable pageable) {

    InvoiceStatus statusFilter = parseStatus(status);
    String term = blankToNull(search);
    LocalDate today = LocalDate.now();

    List<RentInvoice> candidates =
        invoices.findAllByDeletedAtIsNull(Pageable.unpaged()).getContent();
    List<RentInvoice> filtered = new ArrayList<>();
    for (RentInvoice invoice : candidates) {
      if (leaseId != null
          && (invoice.getLease() == null || !leaseId.equals(invoice.getLease().getId()))) {
        continue;
      }
      if (tenantId != null && !tenantId.equals(invoice.getTenantId())) continue;
      if (statusFilter != null && statusFilter != invoice.getStatus()) continue;
      if (from != null
          && (invoice.getPeriodEnd() == null || invoice.getPeriodEnd().isBefore(from))) continue;
      if (to != null
          && (invoice.getPeriodStart() == null || invoice.getPeriodStart().isAfter(to))) continue;
      if (term != null && !contains(invoice.getInvoiceNumber(), term)) continue;
      if (Boolean.TRUE.equals(overdueOnly) && !settlementOf(invoice, today).overdue()) continue;
      filtered.add(invoice);
    }

    filtered.sort(invoiceSort(pageable.getSort()));
    return toPage(filtered, pageable).map(this::toSummary);
  }

  /** One invoice, with its lines and its derived settlement block. */
  @Transactional(readOnly = true)
  public RentInvoiceDtos.RentInvoiceDetail detail(UUID invoiceId) {
    return toDetail(requireInvoice(invoiceId));
  }

  /**
   * The settlement of one invoice, as of a date.
   *
   * <p>The whole point of the class: {@code settled} is the sum of allocations on non-voided
   * {@code PAID} receipts (zero for a {@code VOID} invoice), {@code outstanding} is
   * {@code total - credited - settled}, and {@code overdue} is {@code outstanding &gt; 0} and the due
   * date plus the configured grace period strictly before the as-of date, on a collectable invoice.
   * </p>
   */
  @Transactional(readOnly = true)
  public RentInvoiceDtos.Settlement settlementOf(RentInvoice invoice, LocalDate asOf) {
    return settlement(invoice, allocations.findAllByInvoiceIdFetchPayment(invoice.getId()), asOf);
  }

  /** The same, as of today. */
  @Transactional(readOnly = true)
  public RentInvoiceDtos.Settlement settlementOf(RentInvoice invoice) {
    return settlementOf(invoice, LocalDate.now());
  }

  /**
   * The collections position over a period.
   *
   * <p>The stored figures come from the invoice repository's own derivation, so the board and the
   * service agree; the overdue part is worked out here from the invoices that are still collectable
   * and past their due date plus grace. Void invoices are excluded from every figure.</p>
   */
  @Transactional(readOnly = true)
  public RentInvoiceDtos.CollectionsPosition collectionsPosition(LocalDate from, LocalDate to) {
    accessScope.requireCompanyWide("see the firm's collections position");

    LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate asOf = LocalDate.now();

    RentInvoiceRepository.CollectionsPosition position = invoices.collectionsPosition(start, end);
    BigDecimal invoiced = MoneyPolicy.money(position == null ? null : position.getInvoiced());
    BigDecimal collected = MoneyPolicy.money(position == null ? null : position.getSettled());
    BigDecimal outstanding = MoneyPolicy.money(position == null ? null : position.getOutstanding());

    BigDecimal overdue = MoneyPolicy.zero();
    long overdueCount = 0;
    for (RentInvoice invoice :
        invoices
            .findAllByStatusAndDeletedAtIsNull(InvoiceStatus.ISSUED, Pageable.unpaged())
            .getContent()) {
      if (invoice.getPeriodStart() == null
          || invoice.getPeriodStart().isBefore(start)
          || invoice.getPeriodStart().isAfter(end)) {
        continue;
      }
      RentInvoiceDtos.Settlement settlement = settlementOf(invoice, asOf);
      if (settlement.overdue()) {
        overdue = overdue.add(settlement.outstanding());
        overdueCount++;
      }
    }

    return new RentInvoiceDtos.CollectionsPosition(
        start, end, invoiced, collected, outstanding, MoneyPolicy.money(overdue), overdueCount);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private RentInvoice requireInvoice(UUID invoiceId) {
    return invoices
        .findByIdAndDeletedAtIsNull(invoiceId)
        .orElseThrow(() -> notFound("INVOICE_NOT_FOUND"));
  }

  private RentInvoiceDtos.Settlement settlement(
      RentInvoice invoice, List<PaymentAllocation> allocationsForInvoice, LocalDate asOf) {
    BigDecimal settled = settledAmount(invoice, allocationsForInvoice);
    BigDecimal total = MoneyPolicy.money(invoice.getTotalAmount());
    BigDecimal credited = MoneyPolicy.money(invoice.getCreditedAmount());
    BigDecimal outstanding = MoneyPolicy.money(total.subtract(credited).subtract(settled));
    boolean collectable = invoice.getStatus() == InvoiceStatus.ISSUED;
    boolean overdue =
        collectable
            && outstanding.signum() > 0
            && invoice.getDueDate() != null
            && invoice.getDueDate().plusDays(graceDays()).isBefore(asOf);
    return new RentInvoiceDtos.Settlement(total, credited, settled, outstanding, overdue, collectable);
  }

  /** A void invoice's allocations do not count; otherwise only {@code PAID} payments settle. */
  private static BigDecimal settledAmount(
      RentInvoice invoice, List<PaymentAllocation> allocationsForInvoice) {
    if (invoice.getStatus() == InvoiceStatus.VOID) return MoneyPolicy.zero();
    BigDecimal sum = BigDecimal.ZERO;
    for (PaymentAllocation allocation : allocationsForInvoice) {
      Payment payment = allocation.getPayment();
      if (payment == null || payment.getStatus() != PaymentStatus.PAID) continue;
      sum = sum.add(MoneyPolicy.money(allocation.getAmount()));
    }
    return MoneyPolicy.money(sum);
  }

  private RentInvoiceDtos.RentInvoiceDetail toDetail(RentInvoice invoice) {
    UUID invoiceId = invoice.getId();
    List<PaymentAllocation> allocationsForInvoice =
        allocations.findAllByInvoiceIdFetchPayment(invoiceId);
    RentInvoiceDtos.Settlement settlement = settlement(invoice, allocationsForInvoice, LocalDate.now());

    List<RentInvoiceDtos.InvoiceLineView> lineViews =
        lines.findAllByInvoice_IdAndDeletedAtIsNullOrderByDisplayOrderAsc(invoiceId).stream()
            .map(RentInvoiceService::toLineView)
            .toList();
    List<RentInvoiceDtos.AllocationView> allocationViews =
        allocationsForInvoice.stream().map(RentInvoiceService::toAllocationView).toList();

    Lease lease = invoice.getLease();
    return new RentInvoiceDtos.RentInvoiceDetail(
        invoice.getId(),
        invoice.getInvoiceNumber(),
        lease == null ? null : lease.getId(),
        lease == null ? null : lease.getLeaseCode(),
        invoice.getTenantId(),
        invoice.getProperty() == null ? null : invoice.getProperty().getId(),
        invoice.getUnitId(),
        invoice.getPeriodStart(),
        invoice.getPeriodEnd(),
        invoice.getDueDate(),
        MoneyPolicy.money(invoice.getSubtotal()),
        MoneyPolicy.money(invoice.getTaxAmount()),
        MoneyPolicy.money(invoice.getTotalAmount()),
        MoneyPolicy.money(invoice.getCreditedAmount()),
        invoice.getStatus() == null ? null : invoice.getStatus().name(),
        invoice.getIssuedAt(),
        invoice.getVoidedAt(),
        invoice.getVoidedBy(),
        invoice.getVoidReason(),
        invoice.getWrittenOffAt(),
        invoice.getWrittenOffBy(),
        invoice.getWriteOffReason(),
        invoice.getNotes(),
        settlement,
        lineViews,
        allocationViews);
  }

  private RentInvoiceDtos.RentInvoiceSummary toSummary(RentInvoice invoice) {
    Lease lease = invoice.getLease();
    return new RentInvoiceDtos.RentInvoiceSummary(
        invoice.getId(),
        invoice.getInvoiceNumber(),
        lease == null ? null : lease.getId(),
        lease == null ? null : lease.getLeaseCode(),
        invoice.getTenantId(),
        invoice.getProperty() == null ? null : invoice.getProperty().getId(),
        invoice.getUnitId(),
        invoice.getPeriodStart(),
        invoice.getPeriodEnd(),
        invoice.getDueDate(),
        MoneyPolicy.money(invoice.getSubtotal()),
        MoneyPolicy.money(invoice.getTaxAmount()),
        MoneyPolicy.money(invoice.getTotalAmount()),
        MoneyPolicy.money(invoice.getCreditedAmount()),
        invoice.getStatus() == null ? null : invoice.getStatus().name(),
        invoice.getIssuedAt(),
        invoice.getCreatedAt());
  }

  private static RentInvoiceDtos.InvoiceLineView toLineView(RentInvoiceLine line) {
    return new RentInvoiceDtos.InvoiceLineView(
        line.getId(),
        line.getLineType() == null ? null : line.getLineType().name(),
        line.getDescription(),
        MoneyPolicy.money(line.getQuantity()),
        MoneyPolicy.money(line.getUnitAmount()),
        MoneyPolicy.money(line.getLineTotal()),
        MoneyPolicy.money(line.getTaxRatePercent()),
        MoneyPolicy.money(line.getTaxAmount()),
        line.getDisplayOrder());
  }

  private static RentInvoiceDtos.AllocationView toAllocationView(PaymentAllocation allocation) {
    Payment payment = allocation.getPayment();
    return new RentInvoiceDtos.AllocationView(
        allocation.getId(),
        payment == null ? null : payment.getId(),
        payment == null ? null : payment.getReceiptNumber(),
        MoneyPolicy.money(allocation.getAmount()),
        allocation.getAllocatedAt(),
        allocation.getAllocatedBy());
  }

  /** The due date is the lease's due day in the period's first month, never before the period. */
  private static LocalDate deriveDueDate(LocalDate periodStart, int dueDay) {
    int day = Math.min(Math.max(dueDay, 1), periodStart.lengthOfMonth());
    LocalDate candidate = periodStart.withDayOfMonth(day);
    return candidate.isBefore(periodStart) ? periodStart : candidate;
  }

  private int graceDays() {
    return propertiesConfig.getPortfolio().getRentOverdueGraceDays();
  }

  private void recordAudit(
      User actor, String action, String description, String entityType, UUID entityId, String label) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AUDIT_MODULE,
        action,
        description,
        entityType,
        entityId,
        label);
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static InvoiceStatus parseStatus(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return InvoiceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an invoice status");
    }
  }

  private static RentInvoiceLineType parseLineType(String value) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("A line type is required");
    }
    try {
      return RentInvoiceLineType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a line type");
    }
  }

  private static Comparator<RentInvoice> invoiceSort(Sort sort) {
    Comparator<RentInvoice> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<RentInvoice> next =
          switch (order.getProperty()) {
            case "invoiceNumber" ->
                Comparator.comparing(
                    RentInvoice::getInvoiceNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "dueDate" ->
                Comparator.comparing(
                    RentInvoice::getDueDate, Comparator.nullsLast(LocalDate::compareTo));
            case "periodStart" ->
                Comparator.comparing(
                    RentInvoice::getPeriodStart, Comparator.nullsLast(LocalDate::compareTo));
            case "totalAmount" ->
                Comparator.comparing(
                    RentInvoice::getTotalAmount, Comparator.nullsLast(BigDecimal::compareTo));
            case "status" ->
                Comparator.comparing(
                    (RentInvoice invoice) ->
                        invoice.getStatus() == null ? null : invoice.getStatus().name(),
                    Comparator.nullsLast(String::compareTo));
            case "createdAt" ->
                Comparator.comparing(
                    RentInvoice::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo));
            default -> null;
          };
      if (next == null) continue;
      if (order.isDescending()) next = next.reversed();
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    return comparator == null
        ? Comparator.comparing(
                RentInvoice::getDueDate, Comparator.nullsLast(LocalDate::compareTo))
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
