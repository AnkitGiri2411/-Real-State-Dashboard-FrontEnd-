package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.InvoiceStatus;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.PaymentAllocation;
import com.meridian.realestate.domain.PaymentDirection;
import com.meridian.realestate.domain.PaymentMethod;
import com.meridian.realestate.domain.PaymentStatus;
import com.meridian.realestate.domain.RentInvoice;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.PaymentDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.PaymentAllocationRepository;
import com.meridian.realestate.repository.PaymentMethodRepository;
import com.meridian.realestate.repository.PaymentRepository;
import com.meridian.realestate.repository.RentInvoiceRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment ledger: receipts, the outbound rows that reverse them, and the allocations that make
 * an invoice's settlement derivable.
 *
 * <h2>The two caps the schema does not enforce</h2>
 * <p>Over-allocation spans rows, so it cannot be a row-level {@code CHECK} and the database
 * deliberately does not carry it. The service enforces it, inside one transaction, and both caps are
 * checked here:</p>
 * <ul>
 *   <li>{@code SUM(payment_allocations.amount)} for an invoice may not exceed its
 *       {@code total_amount} - {@code PAYMENT_ALLOCATION_EXCEEDS_INVOICE};</li>
 *   <li>{@code SUM(payment_allocations.amount)} for a receipt may not exceed its {@code amount} -
 *       {@code PAYMENT_ALLOCATION_EXCEEDS_RECEIPT};</li>
 *   <li>the total refunded against a receipt may not exceed that receipt -
 *       {@code REFUND_EXCEEDS_RECEIPT}.</li>
 * </ul>
 *
 * <h2>A refund is a row, not a status</h2>
 * <p>{@code payments.amount} is always positive, so a refund is a separate {@code OUTBOUND} row of
 * the same positive magnitude whose {@code refund_of_payment_id} names the receipt it reverses; a
 * partial refund is therefore representable. That is why "collected" is a <em>signed</em> sum over
 * rows - {@code status = 'PAID'} and signed by {@code direction} - and never a naive
 * {@code SUM(amount)}, which would count a refund as a collection.</p>
 */
@Service
public class PaymentService {

  private final PaymentRepository payments;
  private final PaymentAllocationRepository allocations;
  private final RentInvoiceRepository invoices;
  private final PaymentMethodRepository paymentMethods;
  private final LeaseRepository leases;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;
  private final AppProperties propertiesConfig;

  public PaymentService(
      PaymentRepository payments,
      PaymentAllocationRepository allocations,
      RentInvoiceRepository invoices,
      PaymentMethodRepository paymentMethods,
      LeaseRepository leases,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope,
      AppProperties propertiesConfig) {
    this.payments = payments;
    this.allocations = allocations;
    this.invoices = invoices;
    this.paymentMethods = paymentMethods;
    this.leases = leases;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
    this.propertiesConfig = propertiesConfig;
  }

  // ---------------------------------------------------------------------------
  // Recording
  // ---------------------------------------------------------------------------

  /**
   * Record a receipt: money received.
   *
   * <p>The number is minted, the counterparty resolved, the settlement target checked
   * ({@code ck_payments_settles_one_thing}: a lease XOR a sale) and the currency defaulted. A receipt
   * that has not cleared is recorded {@code PENDING}; one that has is {@code PAID}, and only
   * {@code PAID} counts as collected.</p>
   */
  @Transactional
  public PaymentDtos.PaymentSummary record(PaymentDtos.RecordPaymentRequest request) {
    User actor = accessScope.requireUser();

    BigDecimal amount = MoneyPolicy.money(request.amount());
    if (!MoneyPolicy.isPositive(amount)) {
      throw badRequest("PAYMENT_AMOUNT_INVALID");
    }

    PaymentDirection direction = parseDirection(request.direction());
    if (direction == PaymentDirection.OUTBOUND) {
      throw ApiException.badRequest("A refund is recorded through the refund operation, not as a receipt");
    }
    PaymentStatus status = parseStatus(request.status());

    PaymentMethod method =
        paymentMethods
            .findById(request.paymentMethodId())
            .orElseThrow(() -> ApiException.notFound("We could not find that payment method"));
    if (method.isRequiresReference()
        && (request.reference() == null || request.reference().isBlank())) {
      throw ApiException.badRequest("This payment method requires an instrument reference");
    }

    boolean settlesLease = request.leaseId() != null;
    boolean settlesSale = request.saleId() != null;
    if (settlesLease == settlesSale) {
      throw ApiException.badRequest("A receipt settles either a lease or a sale, not both and not neither");
    }

    Lease lease = null;
    UUID tenantId = request.tenantId();
    if (settlesLease) {
      lease =
          leases
              .findByIdAndDeletedAtIsNull(request.leaseId())
              .orElseThrow(() -> notFound("LEASE_NOT_FOUND"));
      if (tenantId == null) tenantId = lease.getTenantId();
    }
    if (tenantId == null && request.buyerId() == null) {
      throw ApiException.badRequest("A receipt must name the tenant or the buyer it came from");
    }

    Payment payment = new Payment();
    payment.setReceiptNumber(numbers.next(BusinessNumberService.RECEIPT));
    payment.setDirection(PaymentDirection.INBOUND);
    payment.setPaymentMethod(method);
    payment.setTenantId(tenantId);
    payment.setBuyerId(request.buyerId());
    payment.setLease(lease);
    payment.setSaleId(request.saleId());
    if (lease != null) payment.setProperty(lease.getProperty());
    payment.setAmount(amount);
    payment.setCurrency(normaliseCurrency(request.currency()));
    payment.setStatus(status);
    payment.setReceivedAt(request.receivedAt() == null ? LocalDateTime.now() : request.receivedAt());
    payment.setReference(request.reference());
    payment.setNotes(request.notes());
    payment.setRecordedBy(actor.getId());
    payment.setCreatedBy(actor.getId());
    payment.setUpdatedBy(actor.getId());
    Payment saved = payments.save(payment);

    recordAudit(
        actor,
        AuditActions.PAYMENT_RECORDED,
        "Received " + amount + " " + saved.getCurrency() + " by "
            + (method.getName() == null ? method.getCode() : method.getName())
            + (lease == null ? "" : " against lease " + lease.getLeaseCode()),
        "PAYMENT",
        saved.getId(),
        saved.getReceiptNumber());
    return toSummary(saved);
  }

  /**
   * Apply a receipt to one or more invoices, enforcing both allocation caps in one transaction.
   *
   * <p>An invoice may be settled by several receipts and a receipt may settle several invoices, so a
   * second call against the same invoice is ordinary. A pair that already has an allocation is
   * adjusted rather than duplicated - {@code uq_payment_allocations_pair} allows exactly one live row
   * per {@code (payment, invoice)} - and the caps are checked against the <em>projected</em> totals,
   * so the adjustment cannot push either past its ceiling.</p>
   */
  @Transactional
  public PaymentDtos.PaymentDetail allocate(UUID paymentId, PaymentDtos.AllocationRequest request) {
    User actor = accessScope.requireUser();
    Payment payment = requirePayment(paymentId);
    if (payment.getStatus() == PaymentStatus.VOIDED) {
      throw rule("PAYMENT_ALREADY_VOID");
    }
    if (payment.getDirection() == PaymentDirection.OUTBOUND) {
      throw ApiException.badRequest("A refund is not allocated to invoices");
    }
    if (request.allocations() == null || request.allocations().isEmpty()) {
      throw ApiException.badRequest("At least one allocation is required");
    }

    BigDecimal receiptAmount = MoneyPolicy.money(payment.getAmount());
    BigDecimal receiptAllocated = MoneyPolicy.money(allocations.sumAllocatedForPayment(paymentId));

    Map<UUID, PaymentAllocation> existingByInvoice = new HashMap<>();
    for (PaymentAllocation existing : allocations.findAllByPayment_IdAndDeletedAtIsNull(paymentId)) {
      if (existing.getInvoice() != null) {
        existingByInvoice.put(existing.getInvoice().getId(), existing);
      }
    }

    Set<UUID> seen = new HashSet<>();
    BigDecimal projectedReceipt = receiptAllocated;
    List<Applied> applied = new ArrayList<>();

    for (PaymentDtos.AllocationLine line : request.allocations()) {
      if (line.invoiceId() == null) {
        throw ApiException.badRequest("An allocation must name an invoice");
      }
      if (!seen.add(line.invoiceId())) {
        throw ApiException.badRequest("The same invoice appears twice in one allocation");
      }
      BigDecimal amount = MoneyPolicy.money(line.amount());
      if (!MoneyPolicy.isPositive(amount)) {
        throw ApiException.badRequest("An allocation amount must be greater than zero");
      }

      RentInvoice invoice =
          invoices
              .findByIdAndDeletedAtIsNull(line.invoiceId())
              .orElseThrow(() -> notFound("INVOICE_NOT_FOUND"));
      if (invoice.getStatus() == InvoiceStatus.VOID) {
        throw rule("INVOICE_ALREADY_VOID");
      }

      PaymentAllocation existing = existingByInvoice.get(invoice.getId());
      BigDecimal existingAmount =
          existing == null ? MoneyPolicy.zero() : MoneyPolicy.money(existing.getAmount());

      BigDecimal allocatedForInvoice =
          MoneyPolicy.money(allocations.sumAllocatedForInvoice(invoice.getId()));
      BigDecimal projectedInvoice = allocatedForInvoice.subtract(existingAmount).add(amount);
      if (projectedInvoice.compareTo(MoneyPolicy.money(invoice.getTotalAmount())) > 0) {
        throw rule("PAYMENT_ALLOCATION_EXCEEDS_INVOICE");
      }

      projectedReceipt = projectedReceipt.subtract(existingAmount).add(amount);
      applied.add(new Applied(invoice, existing, amount, line.notes()));
    }

    if (projectedReceipt.compareTo(receiptAmount) > 0) {
      throw rule("PAYMENT_ALLOCATION_EXCEEDS_RECEIPT");
    }

    LocalDateTime now = LocalDateTime.now();
    for (Applied application : applied) {
      PaymentAllocation allocation =
          application.existing() == null ? new PaymentAllocation() : application.existing();
      allocation.setPayment(payment);
      allocation.setInvoice(application.invoice());
      allocation.setAmount(application.amount());
      allocation.setNotes(application.notes());
      allocation.setAllocatedAt(now);
      allocation.setAllocatedBy(actor.getId());
      if (allocation.getCreatedBy() == null) allocation.setCreatedBy(actor.getId());
      allocation.setUpdatedBy(actor.getId());
      allocations.save(allocation);
    }

    recordAudit(
        actor,
        AuditActions.CREATE,
        "Applied receipt " + payment.getReceiptNumber() + " to " + applied.size() + " invoice(s)",
        "PAYMENT_ALLOCATION",
        payment.getId(),
        payment.getReceiptNumber());
    return detail(paymentId);
  }

  /** Void a receipt. The row survives and stops counting toward settlement. */
  @Transactional
  public PaymentDtos.PaymentSummary voidPayment(UUID paymentId, String reason) {
    User actor = accessScope.requireUser();
    Payment payment = requirePayment(paymentId);
    if (payment.getStatus() == PaymentStatus.VOIDED) {
      throw rule("PAYMENT_ALREADY_VOID");
    }
    if (reason == null || reason.isBlank()) {
      throw ApiException.badRequest("A reason is required to void a receipt");
    }

    payment.setStatus(PaymentStatus.VOIDED);
    payment.setVoidedAt(LocalDateTime.now());
    payment.setVoidedBy(actor.getId());
    payment.setVoidReason(reason);
    payment.setUpdatedBy(actor.getId());
    Payment saved = payments.save(payment);

    recordAudit(
        actor,
        AuditActions.PAYMENT_VOIDED,
        "Voided receipt " + saved.getReceiptNumber() + " (" + saved.getAmount() + ")",
        "PAYMENT",
        saved.getId(),
        saved.getReceiptNumber());
    return toSummary(saved);
  }

  /**
   * Refund a receipt, in whole or in part, as a new {@code OUTBOUND} row.
   *
   * <p>The total refunded against a receipt may never exceed the receipt itself - it spans rows, so
   * it is checked here, against the sum of the receipt's non-voided refunds, before the row is
   * written. The refund carries the same lease/sale and counterparty as the receipt it reverses, so
   * {@code ck_payments_settles_one_thing} still holds.</p>
   */
  @Transactional
  public PaymentDtos.PaymentSummary refund(PaymentDtos.RefundRequest request) {
    User actor = accessScope.requireUser();

    BigDecimal amount = MoneyPolicy.money(request.amount());
    if (!MoneyPolicy.isPositive(amount)) {
      throw badRequest("PAYMENT_AMOUNT_INVALID");
    }

    Payment original = requirePayment(request.refundOfPaymentId());
    if (original.getDirection() != PaymentDirection.INBOUND) {
      throw ApiException.badRequest("Only an inbound receipt can be refunded");
    }
    if (original.getStatus() == PaymentStatus.VOIDED) {
      throw rule("PAYMENT_ALREADY_VOID");
    }

    BigDecimal alreadyRefunded = MoneyPolicy.money(payments.sumRefundedForPayment(original.getId()));
    if (alreadyRefunded.add(amount).compareTo(MoneyPolicy.money(original.getAmount())) > 0) {
      throw rule("REFUND_EXCEEDS_RECEIPT");
    }

    PaymentMethod method =
        paymentMethods
            .findById(request.paymentMethodId())
            .orElseThrow(() -> ApiException.notFound("We could not find that payment method"));

    Payment refund = new Payment();
    refund.setReceiptNumber(numbers.next(BusinessNumberService.RECEIPT));
    refund.setDirection(PaymentDirection.OUTBOUND);
    refund.setRefundOfPayment(original);
    refund.setPaymentMethod(method);
    refund.setTenantId(original.getTenantId());
    refund.setBuyerId(original.getBuyerId());
    refund.setLease(original.getLease());
    refund.setSaleId(original.getSaleId());
    refund.setProperty(original.getProperty());
    refund.setAmount(amount);
    refund.setCurrency(original.getCurrency());
    refund.setStatus(PaymentStatus.PAID);
    refund.setReceivedAt(request.receivedAt() == null ? LocalDateTime.now() : request.receivedAt());
    refund.setReference(request.reference());
    refund.setNotes(request.notes());
    refund.setRecordedBy(actor.getId());
    refund.setCreatedBy(actor.getId());
    refund.setUpdatedBy(actor.getId());
    Payment saved = payments.save(refund);

    recordAudit(
        actor,
        AuditActions.PAYMENT_RECORDED,
        "Refunded " + amount + " " + saved.getCurrency() + " against receipt " + original.getReceiptNumber(),
        "PAYMENT",
        saved.getId(),
        saved.getReceiptNumber());
    return toSummary(saved);
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** The ledger. Filters are applied over the live receipts, then sorted and paged. */
  @Transactional(readOnly = true)
  public Page<PaymentDtos.PaymentSummary> search(
      UUID leaseId,
      UUID tenantId,
      UUID buyerId,
      UUID saleId,
      String status,
      String direction,
      LocalDate from,
      LocalDate to,
      UUID paymentMethodId,
      String search,
      Pageable pageable) {

    PaymentStatus statusFilter = parseStatusFilter(status);
    PaymentDirection directionFilter = parseDirectionFilter(direction);
    String term = blankToNull(search);

    List<Payment> filtered = new ArrayList<>();
    for (Payment payment : payments.findAllByDeletedAtIsNull(Pageable.unpaged()).getContent()) {
      if (leaseId != null
          && (payment.getLease() == null || !leaseId.equals(payment.getLease().getId()))) {
        continue;
      }
      if (tenantId != null && !tenantId.equals(payment.getTenantId())) continue;
      if (buyerId != null && !buyerId.equals(payment.getBuyerId())) continue;
      if (saleId != null && !saleId.equals(payment.getSaleId())) continue;
      if (statusFilter != null && statusFilter != payment.getStatus()) continue;
      if (directionFilter != null && directionFilter != payment.getDirection()) continue;
      if (from != null
          && (payment.getReceivedAt() == null
              || payment.getReceivedAt().toLocalDate().isBefore(from))) continue;
      if (to != null
          && (payment.getReceivedAt() == null
              || payment.getReceivedAt().toLocalDate().isAfter(to))) continue;
      if (paymentMethodId != null
          && (payment.getPaymentMethod() == null
              || !paymentMethodId.equals(payment.getPaymentMethod().getId()))) continue;
      if (term != null
          && !contains(payment.getReceiptNumber(), term)
          && !contains(payment.getReference(), term)) continue;
      filtered.add(payment);
    }

    filtered.sort(paymentSort(pageable.getSort()));
    return toPage(filtered, pageable).map(this::toSummary);
  }

  /** One receipt, its allocations and how much of it is still unapplied. */
  @Transactional(readOnly = true)
  public PaymentDtos.PaymentDetail detail(UUID paymentId) {
    Payment payment = requirePayment(paymentId);
    List<PaymentAllocation> allocationsForPayment =
        allocations.findAllByPayment_IdAndDeletedAtIsNull(paymentId);

    BigDecimal allocated = MoneyPolicy.zero();
    List<PaymentDtos.AllocationEntry> entries = new ArrayList<>();
    for (PaymentAllocation allocation : allocationsForPayment) {
      allocated = allocated.add(MoneyPolicy.money(allocation.getAmount()));
      RentInvoice invoice = allocation.getInvoice();
      entries.add(
          new PaymentDtos.AllocationEntry(
              allocation.getId(),
              invoice == null ? null : invoice.getId(),
              invoice == null ? null : invoice.getInvoiceNumber(),
              MoneyPolicy.money(allocation.getAmount()),
              allocation.getAllocatedAt(),
              allocation.getAllocatedBy()));
    }
    BigDecimal unallocated =
        MoneyPolicy.money(MoneyPolicy.money(payment.getAmount()).subtract(allocated));
    return new PaymentDtos.PaymentDetail(toSummary(payment), entries, MoneyPolicy.money(allocated), unallocated);
  }

  /**
   * The firm's collections over a period.
   *
   * <p>Only cleared money counts ({@code status = 'PAID'}), and every row is <em>signed</em> by its
   * direction: a receipt adds, a refund subtracts. A naive {@code SUM(amount)} over the ledger would
   * count a refund as a collection and overstate the book - which is exactly the mistake this method
   * exists to make impossible.</p>
   */
  @Transactional(readOnly = true)
  public PaymentDtos.CollectionsSummary collections(LocalDate from, LocalDate to) {
    accessScope.requireCompanyWide("see the firm's collections");

    LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
    LocalDate end = to == null ? LocalDate.now() : to;

    BigDecimal collected = MoneyPolicy.zero();
    BigDecimal refunded = MoneyPolicy.zero();
    long receiptCount = 0;
    long refundCount = 0;

    for (Payment payment :
        payments
            .findAllByStatusAndDeletedAtIsNull(PaymentStatus.PAID, Pageable.unpaged())
            .getContent()) {
      if (payment.getStatus() != PaymentStatus.PAID) continue;
      if (payment.getReceivedAt() == null) continue;
      LocalDate day = payment.getReceivedAt().toLocalDate();
      if (day.isBefore(start) || day.isAfter(end)) continue;

      BigDecimal amount = MoneyPolicy.money(payment.getAmount());
      if (payment.getDirection() == PaymentDirection.OUTBOUND) {
        refunded = refunded.add(amount);
        refundCount++;
      } else {
        collected = collected.add(amount);
        receiptCount++;
      }
    }

    return new PaymentDtos.CollectionsSummary(
        start,
        end,
        collected,
        refunded,
        MoneyPolicy.money(collected.subtract(refunded)),
        receiptCount,
        refundCount);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private Payment requirePayment(UUID paymentId) {
    return payments
        .findByIdAndDeletedAtIsNull(paymentId)
        .orElseThrow(() -> notFound("PAYMENT_NOT_FOUND"));
  }

  private PaymentDtos.PaymentSummary toSummary(Payment payment) {
    PaymentMethod method = payment.getPaymentMethod();
    Lease lease = payment.getLease();
    return new PaymentDtos.PaymentSummary(
        payment.getId(),
        payment.getReceiptNumber(),
        payment.getDirection() == null ? null : payment.getDirection().name(),
        payment.getRefundOfPayment() == null ? null : payment.getRefundOfPayment().getId(),
        payment.getStatus() == null ? null : payment.getStatus().name(),
        MoneyPolicy.money(payment.getAmount()),
        payment.getCurrency(),
        method == null ? null : method.getId(),
        method == null ? null : method.getCode(),
        method == null ? null : method.getName(),
        payment.getTenantId(),
        payment.getBuyerId(),
        lease == null ? null : lease.getId(),
        payment.getSaleId(),
        payment.getProperty() == null ? null : payment.getProperty().getId(),
        payment.getReceivedAt(),
        payment.getReference(),
        payment.getNotes(),
        payment.getRecordedBy(),
        payment.getVoidedAt(),
        payment.getVoidedBy(),
        payment.getVoidReason(),
        payment.getCreatedAt());
  }

  private void recordAudit(
      User actor, String action, String description, String entityType, UUID entityId, String label) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PAYMENT,
        action,
        description,
        entityType,
        entityId,
        label);
  }

  private String normaliseCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      return propertiesConfig.getMoney().getCurrency();
    }
    String normalised = currency.trim().toUpperCase(Locale.ROOT);
    if (normalised.length() != 3) {
      throw ApiException.badRequest("A currency code must be three letters");
    }
    return normalised;
  }

  private static PaymentDirection parseDirection(String value) {
    if (value == null || value.isBlank()) return PaymentDirection.INBOUND;
    try {
      return PaymentDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a payment direction");
    }
  }

  private static PaymentStatus parseStatus(String value) {
    if (value == null || value.isBlank()) return PaymentStatus.PAID;
    PaymentStatus status;
    try {
      status = PaymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a receipt status");
    }
    if (status != PaymentStatus.PENDING && status != PaymentStatus.PAID) {
      throw ApiException.badRequest("A receipt is recorded as PENDING or PAID");
    }
    return status;
  }

  private static PaymentStatus parseStatusFilter(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return PaymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a receipt status");
    }
  }

  private static PaymentDirection parseDirectionFilter(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return PaymentDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a payment direction");
    }
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static Comparator<Payment> paymentSort(Sort sort) {
    Comparator<Payment> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Payment> next =
          switch (order.getProperty()) {
            case "receiptNumber" ->
                Comparator.comparing(
                    Payment::getReceiptNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "amount" ->
                Comparator.comparing(Payment::getAmount, Comparator.nullsLast(BigDecimal::compareTo));
            case "status" ->
                Comparator.comparing(
                    (Payment payment) ->
                        payment.getStatus() == null ? null : payment.getStatus().name(),
                    Comparator.nullsLast(String::compareTo));
            case "direction" ->
                Comparator.comparing(
                    (Payment payment) ->
                        payment.getDirection() == null ? null : payment.getDirection().name(),
                    Comparator.nullsLast(String::compareTo));
            case "receivedAt" ->
                Comparator.comparing(
                    Payment::getReceivedAt, Comparator.nullsLast(LocalDateTime::compareTo));
            case "createdAt" ->
                Comparator.comparing(
                    Payment::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo));
            default -> null;
          };
      if (next == null) continue;
      if (order.isDescending()) next = next.reversed();
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    return comparator == null
        ? Comparator.comparing(
                Payment::getReceivedAt, Comparator.nullsLast(LocalDateTime::compareTo))
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

  private static ApiException badRequest(String code) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, ErrorMessages.friendly(code));
  }

  private static ApiException notFound(String code) {
    return new ApiException(HttpStatus.NOT_FOUND, code, ErrorMessages.friendly(code));
  }

  /** One validated allocation waiting to be written. */
  private record Applied(
      RentInvoice invoice, PaymentAllocation existing, BigDecimal amount, String notes) {}
}
