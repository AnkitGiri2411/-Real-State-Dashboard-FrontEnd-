package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.InvoiceStatus;
import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.PaymentDirection;
import com.meridian.realestate.domain.PaymentStatus;
import com.meridian.realestate.domain.RentInvoice;
import com.meridian.realestate.domain.User;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * The payment ledger's rules, with the repositories mocked and no database.
 *
 * <p>What is pinned here is exactly what the schema cannot enforce because it spans rows: the two
 * allocation caps and the refund cap, each refused before a row is written; that a receipt amount
 * must be positive; and that collections honour {@code status = 'PAID'} <em>and</em> sign every row
 * by its direction, so a refund does not increase what was collected. A naive {@code SUM(amount)}
 * would pass an invoice's settlement and still overstate the book - which is why the sign is
 * asserted, not just the total.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService: the caps and the signed collections")
class PaymentServiceTest {

  @Mock private PaymentRepository payments;
  @Mock private PaymentAllocationRepository allocations;
  @Mock private RentInvoiceRepository invoices;
  @Mock private PaymentMethodRepository paymentMethods;
  @Mock private LeaseRepository leases;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private PaymentService service;

  @BeforeEach
  void setUp() {
    service =
        new PaymentService(
            payments,
            allocations,
            invoices,
            paymentMethods,
            leases,
            numbers,
            audit,
            accessScope,
            new AppProperties());
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Deepa");
    user.setLastName("Venkatesan");
    return user;
  }

  private static Payment receipt(String amount, PaymentDirection direction, PaymentStatus status) {
    Payment payment = new Payment();
    payment.setId(UUID.randomUUID());
    payment.setReceiptNumber("ME-PAY-000001");
    payment.setAmount(new BigDecimal(amount));
    payment.setDirection(direction);
    payment.setStatus(status);
    payment.setReceivedAt(LocalDateTime.now());
    return payment;
  }

  private static RentInvoice invoice(String total) {
    RentInvoice invoice = new RentInvoice();
    invoice.setId(UUID.randomUUID());
    invoice.setInvoiceNumber("ME-R-000001");
    invoice.setTotalAmount(new BigDecimal(total));
    invoice.setStatus(InvoiceStatus.ISSUED);
    return invoice;
  }

  // ------------------------------------------------------------------- receipt

  @Test
  @DisplayName("a zero-amount receipt is refused (amount must be positive)")
  void aZeroAmountReceiptIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());

    PaymentDtos.RecordPaymentRequest request =
        new PaymentDtos.RecordPaymentRequest(
            UUID.randomUUID(), BigDecimal.ZERO, "INBOUND", null, null, null, null, null, null, null,
            null, null, null);

    assertThatThrownBy(() -> service.record(request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("PAYMENT_AMOUNT_INVALID");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
  }

  // ---------------------------------------------------------------- allocation

  @Test
  @DisplayName("allocating more than the invoice owes is refused with PAYMENT_ALLOCATION_EXCEEDS_INVOICE")
  void allocatingMoreThanTheInvoiceOwesIsRefused() {
    User user = actor();
    when(accessScope.requireUser()).thenReturn(user);
    Payment payment = receipt("500.00", PaymentDirection.INBOUND, PaymentStatus.PAID);
    RentInvoice invoice = invoice("100.00");
    when(payments.findByIdAndDeletedAtIsNull(payment.getId())).thenReturn(Optional.of(payment));
    when(allocations.sumAllocatedForPayment(payment.getId())).thenReturn(new BigDecimal("0.00"));
    when(allocations.findAllByPayment_IdAndDeletedAtIsNull(payment.getId())).thenReturn(List.of());
    when(invoices.findByIdAndDeletedAtIsNull(invoice.getId())).thenReturn(Optional.of(invoice));
    when(allocations.sumAllocatedForInvoice(invoice.getId())).thenReturn(new BigDecimal("0.00"));

    PaymentDtos.AllocationRequest request =
        new PaymentDtos.AllocationRequest(
            List.of(new PaymentDtos.AllocationLine(invoice.getId(), new BigDecimal("150.00"), null)));

    assertThatThrownBy(() -> service.allocate(payment.getId(), request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("PAYMENT_ALLOCATION_EXCEEDS_INVOICE");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  @DisplayName("allocating more than the receipt is refused with PAYMENT_ALLOCATION_EXCEEDS_RECEIPT")
  void allocatingMoreThanTheReceiptIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    Payment payment = receipt("100.00", PaymentDirection.INBOUND, PaymentStatus.PAID);
    RentInvoice invoice = invoice("1000.00");
    when(payments.findByIdAndDeletedAtIsNull(payment.getId())).thenReturn(Optional.of(payment));
    when(allocations.sumAllocatedForPayment(payment.getId())).thenReturn(new BigDecimal("0.00"));
    when(allocations.findAllByPayment_IdAndDeletedAtIsNull(payment.getId())).thenReturn(List.of());
    when(invoices.findByIdAndDeletedAtIsNull(invoice.getId())).thenReturn(Optional.of(invoice));
    when(allocations.sumAllocatedForInvoice(invoice.getId())).thenReturn(new BigDecimal("0.00"));

    PaymentDtos.AllocationRequest request =
        new PaymentDtos.AllocationRequest(
            List.of(new PaymentDtos.AllocationLine(invoice.getId(), new BigDecimal("150.00"), null)));

    assertThatThrownBy(() -> service.allocate(payment.getId(), request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("PAYMENT_ALLOCATION_EXCEEDS_RECEIPT");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  // --------------------------------------------------------------------- refund

  @Test
  @DisplayName("a refund exceeding its receipt is refused with REFUND_EXCEEDS_RECEIPT")
  void aRefundExceedingItsReceiptIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    Payment original = receipt("100.00", PaymentDirection.INBOUND, PaymentStatus.PAID);
    when(payments.findByIdAndDeletedAtIsNull(original.getId())).thenReturn(Optional.of(original));
    when(payments.sumRefundedForPayment(original.getId())).thenReturn(new BigDecimal("80.00"));

    PaymentDtos.RefundRequest request =
        new PaymentDtos.RefundRequest(
            original.getId(), new BigDecimal("50.00"), UUID.randomUUID(), null, null, null);

    assertThatThrownBy(() -> service.refund(request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("REFUND_EXCEEDS_RECEIPT");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  // ---------------------------------------------------------------- collections

  @Test
  @DisplayName("collections honour status=PAID and sign by direction: a refund does not increase collected")
  void collectionsHonourPaidAndSignByDirection() {
    LocalDate from = LocalDate.of(2026, 3, 1);
    LocalDate to = LocalDate.of(2026, 3, 31);

    Payment inbound = receipt("1000.00", PaymentDirection.INBOUND, PaymentStatus.PAID);
    inbound.setReceivedAt(LocalDateTime.of(2026, 3, 10, 9, 0));
    Payment refund = receipt("400.00", PaymentDirection.OUTBOUND, PaymentStatus.PAID);
    refund.setReceivedAt(LocalDateTime.of(2026, 3, 12, 9, 0));
    Payment pending = receipt("250.00", PaymentDirection.INBOUND, PaymentStatus.PENDING);
    pending.setReceivedAt(LocalDateTime.of(2026, 3, 15, 9, 0));
    Payment outsideRange = receipt("9999.00", PaymentDirection.INBOUND, PaymentStatus.PAID);
    outsideRange.setReceivedAt(LocalDateTime.of(2026, 1, 1, 9, 0));

    when(payments.findAllByStatusAndDeletedAtIsNull(eq(PaymentStatus.PAID), any(Pageable.class)))
        .thenReturn(
            new org.springframework.data.domain.PageImpl<>(List.of(inbound, refund, pending, outsideRange)));

    PaymentDtos.CollectionsSummary summary = service.collections(from, to);

    assertThat(summary.collected()).isEqualByComparingTo("1000.00");
    assertThat(summary.refunded()).isEqualByComparingTo("400.00");
    assertThat(summary.netCollected()).isEqualByComparingTo("600.00");
    assertThat(summary.receiptCount()).isEqualTo(1);
    assertThat(summary.refundCount()).isEqualTo(1);

    // The signed identity, asserted explicitly: collected minus refunded, never a naive sum.
    assertThat(summary.netCollected())
        .isEqualByComparingTo(summary.collected().subtract(summary.refunded()));
    // The store filter is what excludes a PENDING receipt; asserted so the rule cannot be dropped.
    verify(payments).findAllByStatusAndDeletedAtIsNull(eq(PaymentStatus.PAID), any(Pageable.class));
  }
}
