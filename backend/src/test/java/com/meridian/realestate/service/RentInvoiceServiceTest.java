package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.InvoiceStatus;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.PaymentAllocation;
import com.meridian.realestate.domain.PaymentStatus;
import com.meridian.realestate.domain.RentInvoice;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.RentInvoiceDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.PaymentAllocationRepository;
import com.meridian.realestate.repository.RentInvoiceLineRepository;
import com.meridian.realestate.repository.RentInvoiceRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The settlement derivation, with the repositories mocked and no database.
 *
 * <p>These are the ledger rules the whole rent side rests on, pinned one at a time: an invoice's
 * settlement is <em>derived</em>, never stored; a partial payment moves outstanding by exactly the
 * amount allocated; a fully settled invoice owes nothing; a {@code VOID} invoice's allocations do
 * not count; and an invoice is overdue only once its due date <em>plus the configured grace
 * period</em> has strictly passed - the boundary day itself is not overdue. Each is cheap to break
 * with a plausible-looking refactor and expensive to notice, which is why each is asserted here.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RentInvoiceService: the derived settlement ledger")
class RentInvoiceServiceTest {

  @Mock private RentInvoiceRepository invoices;
  @Mock private RentInvoiceLineRepository lines;
  @Mock private PaymentAllocationRepository allocations;
  @Mock private LeaseRepository leases;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private AppProperties properties;
  private RentInvoiceService service;

  @BeforeEach
  void setUp() {
    properties = new AppProperties();
    service =
        new RentInvoiceService(
            invoices, lines, allocations, leases, numbers, audit, accessScope, properties);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Deepa");
    user.setLastName("Venkatesan");
    return user;
  }

  private static RentInvoice invoice(
      String total, String credited, InvoiceStatus status, LocalDate dueDate) {
    RentInvoice invoice = new RentInvoice();
    invoice.setId(UUID.randomUUID());
    invoice.setTotalAmount(new BigDecimal(total));
    invoice.setCreditedAmount(new BigDecimal(credited));
    invoice.setStatus(status);
    invoice.setDueDate(dueDate);
    return invoice;
  }

  private static PaymentAllocation allocation(String amount, PaymentStatus status) {
    Payment payment = new Payment();
    payment.setStatus(status);
    PaymentAllocation allocation = new PaymentAllocation();
    allocation.setPayment(payment);
    allocation.setAmount(new BigDecimal(amount));
    return allocation;
  }

  // ---------------------------------------------------------------- derivation

  @Test
  @DisplayName("outstanding is total - credited - settled")
  void outstandingIsTotalMinusCreditedMinusSettled() {
    RentInvoice invoice = invoice("1000.00", "100.00", InvoiceStatus.ISSUED, LocalDate.now().plusDays(10));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId()))
        .thenReturn(List.of(allocation("300.00", PaymentStatus.PAID)));

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, LocalDate.now());

    assertThat(settlement.totalAmount()).isEqualByComparingTo("1000.00");
    assertThat(settlement.creditedAmount()).isEqualByComparingTo("100.00");
    assertThat(settlement.settled()).isEqualByComparingTo("300.00");
    assertThat(settlement.outstanding()).isEqualByComparingTo("600.00");
  }

  @Test
  @DisplayName("a fully settled invoice has zero outstanding")
  void fullySettledHasZeroOutstanding() {
    RentInvoice invoice = invoice("1000.00", "0.00", InvoiceStatus.ISSUED, LocalDate.now().plusDays(10));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId()))
        .thenReturn(List.of(allocation("1000.00", PaymentStatus.PAID)));

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, LocalDate.now());

    assertThat(settlement.settled()).isEqualByComparingTo("1000.00");
    assertThat(settlement.outstanding()).isEqualByComparingTo("0.00");
    assertThat(settlement.outstanding().signum()).isZero();
  }

  @Test
  @DisplayName("a partial payment reduces outstanding by exactly the amount allocated and stays positive")
  void aPartialPaymentReducesOutstandingByExactlyTheAllocatedAmount() {
    RentInvoice invoice = invoice("500.00", "0.00", InvoiceStatus.ISSUED, LocalDate.now().plusDays(10));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId()))
        .thenReturn(List.of(allocation("200.00", PaymentStatus.PAID)));

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, LocalDate.now());

    assertThat(settlement.outstanding()).isEqualByComparingTo("300.00");
    assertThat(settlement.outstanding().signum()).isPositive();
  }

  @Test
  @DisplayName("a PENDING receipt's allocation does not settle an invoice")
  void aPendingReceiptsAllocationDoesNotSettle() {
    RentInvoice invoice = invoice("500.00", "0.00", InvoiceStatus.ISSUED, LocalDate.now().plusDays(10));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId()))
        .thenReturn(List.of(allocation("500.00", PaymentStatus.PENDING)));

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, LocalDate.now());

    assertThat(settlement.settled()).isEqualByComparingTo("0.00");
    assertThat(settlement.outstanding()).isEqualByComparingTo("500.00");
  }

  @Test
  @DisplayName("a VOID invoice's allocations are excluded from settled")
  void aVoidInvoicesAllocationsAreExcludedFromSettled() {
    RentInvoice invoice = invoice("1000.00", "0.00", InvoiceStatus.VOID, LocalDate.now().plusDays(10));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId()))
        .thenReturn(List.of(allocation("1000.00", PaymentStatus.PAID)));

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, LocalDate.now());

    assertThat(settlement.settled()).isEqualByComparingTo("0.00");
    assertThat(settlement.outstanding()).isEqualByComparingTo("1000.00");
  }

  // ------------------------------------------------------------------- overdue

  @Test
  @DisplayName("overdue is false on the exact boundary day (due + grace == today)")
  void overdueIsFalseOnTheBoundaryDay() {
    int grace = properties.getPortfolio().getRentOverdueGraceDays();
    LocalDate asOf = LocalDate.of(2026, 3, 10);
    RentInvoice invoice =
        invoice("500.00", "0.00", InvoiceStatus.ISSUED, asOf.minusDays(grace));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId())).thenReturn(List.of());

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, asOf);

    assertThat(invoice.getDueDate().plusDays(grace)).isEqualTo(asOf);
    assertThat(settlement.overdue()).isFalse();
  }

  @Test
  @DisplayName("overdue is true once due + grace is strictly before today")
  void overdueIsTrueAfterGraceHasElapsed() {
    int grace = properties.getPortfolio().getRentOverdueGraceDays();
    LocalDate asOf = LocalDate.of(2026, 3, 10);
    RentInvoice invoice =
        invoice("500.00", "0.00", InvoiceStatus.ISSUED, asOf.minusDays(grace).minusDays(1));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId())).thenReturn(List.of());

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, asOf);

    assertThat(settlement.outstanding().signum()).isPositive();
    assertThat(settlement.overdue()).isTrue();
  }

  @Test
  @DisplayName("an invoice that owes nothing is never overdue, however long past its due date")
  void aSettledInvoiceIsNeverOverdue() {
    RentInvoice invoice = invoice("500.00", "0.00", InvoiceStatus.ISSUED, LocalDate.now().minusDays(400));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId()))
        .thenReturn(List.of(allocation("500.00", PaymentStatus.PAID)));

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, LocalDate.now());

    assertThat(settlement.outstanding().signum()).isZero();
    assertThat(settlement.overdue()).isFalse();
  }

  // ---------------------------------------------------------------- reconcile

  @Test
  @DisplayName("lines that do not sum to the stated subtotal are refused with INVOICE_LINES_DO_NOT_RECONCILE")
  void linesThatDoNotSumToTheSubtotalAreRefused() {
    User user = actor();
    when(accessScope.requireUser()).thenReturn(user);
    Lease lease = new Lease();
    lease.setId(UUID.randomUUID());
    lease.setLeaseCode("ME-L-00001");
    when(leases.findByIdAndDeletedAtIsNull(lease.getId())).thenReturn(Optional.of(lease));

    RentInvoiceDtos.IssueInvoiceRequest request =
        new RentInvoiceDtos.IssueInvoiceRequest(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            new BigDecimal("900.00"), // stated, but the line below is 1000.00
            null,
            List.of(
                new RentInvoiceDtos.InvoiceLineRequest(
                    "RENT", "Rent for March", BigDecimal.ONE, new BigDecimal("1000.00"), null, null)));

    assertThatThrownBy(() -> service.issue(lease.getId(), request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("INVOICE_LINES_DO_NOT_RECONCILE");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  @DisplayName("money is scale 2 with HALF_UP at the settlement boundary")
  void moneyIsScaleTwoHalfUp() {
    RentInvoice invoice =
        invoice("1000.005", "0.005", InvoiceStatus.ISSUED, LocalDate.now().plusDays(10));
    when(allocations.findAllByInvoiceIdFetchPayment(invoice.getId()))
        .thenReturn(List.of(allocation("0.005", PaymentStatus.PAID)));

    RentInvoiceDtos.Settlement settlement = service.settlementOf(invoice, LocalDate.now());

    assertThat(settlement.totalAmount()).isEqualByComparingTo("1000.01");
    assertThat(settlement.totalAmount().scale()).isEqualTo(2);
    assertThat(settlement.creditedAmount()).isEqualByComparingTo("0.01");
    assertThat(settlement.settled()).isEqualByComparingTo("0.01");
    assertThat(settlement.outstanding().scale()).isEqualTo(2);
    assertThat(settlement.outstanding()).isEqualByComparingTo("999.99");
  }
}
