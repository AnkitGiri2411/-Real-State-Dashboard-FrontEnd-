package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.RentInvoiceDtos;
import com.meridian.realestate.service.RentInvoiceService;
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
 * Rent invoices: raising them, voiding them, writing them off, and the collections they feed.
 *
 * <p>Raising an invoice needs {@code RENT_GENERATE}; voiding or writing one off needs
 * {@code RENT_UPDATE}; reading them needs {@code RENT_VIEW}. The permission codes are the ones
 * seeded in {@code V2}. Nothing about whether an invoice is paid is written or returned as a stored
 * field - the settlement block is derived by {@link RentInvoiceService}.</p>
 */
@RestController
@RequestMapping("/api/v1/rent-invoices")
public class RentInvoiceController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("dueDate,desc")
          .allow("invoiceNumber", "invoiceNumber")
          .allow("dueDate", "dueDate")
          .allow("periodStart", "periodStart")
          .allow("totalAmount", "totalAmount")
          .allow("status", "status")
          .allow("createdAt", "createdAt")
          .build();

  private final RentInvoiceService rentInvoiceService;

  public RentInvoiceController(RentInvoiceService rentInvoiceService) {
    this.rentInvoiceService = rentInvoiceService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('RENT_VIEW')")
  public ApiResponse<PageResponse<RentInvoiceDtos.RentInvoiceSummary>> list(
      @RequestParam(required = false) UUID leaseId,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            rentInvoiceService.search(
                leaseId,
                tenantId,
                status,
                from,
                to,
                overdueOnly,
                search,
                PAGES.toPageable(page, size, sort))));
  }

  /**
   * The collections position over a date range: invoiced, collected, outstanding and overdue.
   *
   * <p>Firm-wide - a scoped agent may not read the whole book's collections, so the service refuses a
   * non-company-wide caller with a 403.</p>
   */
  @GetMapping("/collections")
  @PreAuthorize("hasAuthority('RENT_VIEW')")
  public ApiResponse<RentInvoiceDtos.CollectionsPosition> collections(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(rentInvoiceService.collectionsPosition(from, to));
  }

  /** One invoice with its lines and its derived settlement block. */
  @GetMapping("/{invoiceId}")
  @PreAuthorize("hasAuthority('RENT_VIEW')")
  public ApiResponse<RentInvoiceDtos.RentInvoiceDetail> detail(@PathVariable UUID invoiceId) {
    return ApiResponse.ok(rentInvoiceService.detail(invoiceId));
  }

  /** Raise an invoice for a lease and a period. The lines are reconciled to the subtotal. */
  @PostMapping
  @PreAuthorize("hasAuthority('RENT_GENERATE')")
  public ApiResponse<RentInvoiceDtos.RentInvoiceDetail> issue(
      @RequestParam UUID leaseId, @Valid @RequestBody RentInvoiceDtos.IssueInvoiceRequest request) {
    return ApiResponse.ok(rentInvoiceService.issue(leaseId, request), "Invoice issued");
  }

  /** Void an invoice. Its allocations stop counting toward settlement. */
  @PostMapping("/{invoiceId}/void")
  @PreAuthorize("hasAuthority('RENT_UPDATE')")
  public ApiResponse<RentInvoiceDtos.RentInvoiceDetail> voidInvoice(
      @PathVariable UUID invoiceId, @Valid @RequestBody RentInvoiceDtos.VoidInvoiceRequest request) {
    return ApiResponse.ok(rentInvoiceService.voidInvoice(invoiceId, request.reason()), "Invoice voided");
  }

  /** Write an invoice off as uncollectable. */
  @PostMapping("/{invoiceId}/write-off")
  @PreAuthorize("hasAuthority('RENT_UPDATE')")
  public ApiResponse<RentInvoiceDtos.RentInvoiceDetail> writeOff(
      @PathVariable UUID invoiceId, @Valid @RequestBody RentInvoiceDtos.WriteOffInvoiceRequest request) {
    return ApiResponse.ok(rentInvoiceService.writeOff(invoiceId, request.reason()), "Invoice written off");
  }
}
