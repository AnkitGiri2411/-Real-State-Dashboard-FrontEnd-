package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.PaymentDtos;
import com.meridian.realestate.service.PaymentService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment ledger: receipts, the outbound rows that reverse them, and the allocations that make
 * an invoice's settlement derivable.
 *
 * <p>Recording, allocating, voiding and refunding are four separate permissions - a clerk who takes
 * a payment is not thereby able to reverse yesterday's receipt, nor to send money back. The codes
 * are the ones seeded in {@code V2} ({@code PAYMENT_VIEW}, {@code PAYMENT_RECORD},
 * {@code PAYMENT_VOID}, {@code PAYMENT_REFUND}). No business rule lives here; every cap and every
 * sign is in {@link PaymentService}.</p>
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("receivedAt,desc")
          .allow("receiptNumber", "receiptNumber")
          .allow("amount", "amount")
          .allow("status", "status")
          .allow("direction", "direction")
          .allow("receivedAt", "receivedAt")
          .allow("createdAt", "createdAt")
          .build();

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
  public ApiResponse<PageResponse<PaymentDtos.PaymentSummary>> list(
      @RequestParam(required = false) UUID leaseId,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) UUID buyerId,
      @RequestParam(required = false) UUID saleId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) UUID paymentMethodId,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            paymentService.search(
                leaseId,
                tenantId,
                buyerId,
                saleId,
                status,
                direction,
                from,
                to,
                paymentMethodId,
                search,
                PAGES.toPageable(page, size, sort))));
  }

  /**
   * The firm's collections over a date range.
   *
   * <p>Honours {@code status = 'PAID'} and signs by direction, so a refund does not increase the
   * collected figure. Firm-wide - a scoped agent is refused with a 403 by the service.</p>
   */
  @GetMapping("/collections")
  @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
  public ApiResponse<PaymentDtos.CollectionsSummary> collections(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(paymentService.collections(from, to));
  }

  /** One receipt with its allocations and how much is still unapplied. */
  @GetMapping("/{paymentId}")
  @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
  public ApiResponse<PaymentDtos.PaymentDetail> detail(@PathVariable UUID paymentId) {
    return ApiResponse.ok(paymentService.detail(paymentId));
  }

  /** Record a receipt. The number is minted server-side and the currency defaults to the firm's. */
  @PostMapping
  @PreAuthorize("hasAuthority('PAYMENT_RECORD')")
  public ApiResponse<PaymentDtos.PaymentSummary> record(
      @Valid @RequestBody PaymentDtos.RecordPaymentRequest request) {
    return ApiResponse.ok(paymentService.record(request), "Payment recorded");
  }

  /** Apply a receipt to one or more invoices, enforcing both allocation caps. */
  @PostMapping("/{paymentId}/allocations")
  @PreAuthorize("hasAuthority('PAYMENT_RECORD')")
  public ApiResponse<PaymentDtos.PaymentDetail> allocate(
      @PathVariable UUID paymentId, @Valid @RequestBody PaymentDtos.AllocationRequest request) {
    return ApiResponse.ok(paymentService.allocate(paymentId, request), "Allocation recorded");
  }

  /** Void a receipt. The row survives and stops counting toward settlement. */
  @PostMapping("/{paymentId}/void")
  @PreAuthorize("hasAuthority('PAYMENT_VOID')")
  public ApiResponse<PaymentDtos.PaymentSummary> voidPayment(
      @PathVariable UUID paymentId, @Valid @RequestBody PaymentDtos.VoidPaymentRequest request) {
    return ApiResponse.ok(paymentService.voidPayment(paymentId, request.reason()), "Payment voided");
  }

  /** Refund a receipt, in whole or in part, as a new outbound row. */
  @PostMapping("/refunds")
  @PreAuthorize("hasAuthority('PAYMENT_REFUND')")
  public ApiResponse<PaymentDtos.PaymentSummary> refund(
      @Valid @RequestBody PaymentDtos.RefundRequest request) {
    return ApiResponse.ok(paymentService.refund(request), "Refund recorded");
  }
}
