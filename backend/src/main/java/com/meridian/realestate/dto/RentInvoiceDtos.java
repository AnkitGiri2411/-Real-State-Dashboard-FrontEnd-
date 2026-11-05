package com.meridian.realestate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The rent invoice API's contracts.
 *
 * <h2>What is here, and what is deliberately not</h2>
 * <p>An invoice's {@code subtotal}, {@code tax_amount}, {@code total_amount} and
 * {@code credited_amount} are facts about a document, so they are carried. Everything about whether
 * it has been <em>paid</em> - {@code settled}, {@code outstanding} and {@code overdue} - is not a
 * column anywhere; it is carried on {@link Settlement} as a value the service <em>derives</em> from
 * the allocations posted against the invoice. There is no {@code PAID} status, because there is no
 * stored settlement to disagree with the ledger.</p>
 *
 * <p><b>Money is {@link BigDecimal} only.</b> A line's {@code unitAmount} and {@code lineTotal} are
 * free to be negative: a credit line is a real thing, and this contract does not assume
 * positivity.</p>
 */
public final class RentInvoiceDtos {

  private RentInvoiceDtos() {}

  // ---------------------------------------------------------------------------
  // Read models
  // ---------------------------------------------------------------------------

  /** A row in the invoice register. */
  public record RentInvoiceSummary(
      UUID id,
      String invoiceNumber,
      UUID leaseId,
      String leaseCode,
      UUID tenantId,
      UUID propertyId,
      UUID unitId,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate dueDate,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      BigDecimal creditedAmount,
      String status,
      LocalDateTime issuedAt,
      LocalDateTime createdAt) {}

  /** One line of an invoice. A line may be negative; nothing here assumes otherwise. */
  public record InvoiceLineView(
      UUID id,
      String lineType,
      String description,
      BigDecimal quantity,
      BigDecimal unitAmount,
      BigDecimal lineTotal,
      BigDecimal taxRatePercent,
      BigDecimal taxAmount,
      int displayOrder) {}

  /** One allocation posted against an invoice, naming the receipt that settled it. */
  public record AllocationView(
      UUID id,
      UUID paymentId,
      String receiptNumber,
      BigDecimal amount,
      LocalDateTime allocatedAt,
      UUID allocatedBy) {}

  /**
   * The derived settlement of an invoice - the three figures the database deliberately does not
   * store.
   *
   * @param totalAmount the invoice's stored total
   * @param creditedAmount the invoice's stored credit (a write-off or goodwill credit)
   * @param settled the sum of allocations on non-voided {@code PAID} receipts; zero for a
   *     {@code VOID} invoice, whose allocations are excluded
   * @param outstanding {@code totalAmount - creditedAmount - settled}
   * @param overdue {@code outstanding > 0} and the due date plus the configured grace period is
   *     strictly before the as-of date, on an invoice that is still collectable
   * @param collectable true only for an {@code ISSUED} invoice
   */
  public record Settlement(
      BigDecimal totalAmount,
      BigDecimal creditedAmount,
      BigDecimal settled,
      BigDecimal outstanding,
      boolean overdue,
      boolean collectable) {}

  /** Everything the invoice detail screen reads, including its lines and its settlement. */
  public record RentInvoiceDetail(
      UUID id,
      String invoiceNumber,
      UUID leaseId,
      String leaseCode,
      UUID tenantId,
      UUID propertyId,
      UUID unitId,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate dueDate,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      BigDecimal creditedAmount,
      String status,
      LocalDateTime issuedAt,
      LocalDateTime voidedAt,
      UUID voidedBy,
      String voidReason,
      LocalDateTime writtenOffAt,
      UUID writtenOffBy,
      String writeOffReason,
      String notes,
      Settlement settlement,
      List<InvoiceLineView> lines,
      List<AllocationView> allocations) {}

  /**
   * The collections position over a period.
   *
   * @param invoiced total billed on invoices whose period falls in the range, void invoices
   *     excluded
   * @param collected what has been settled against those invoices
   * @param outstanding {@code invoiced - credited - collected}
   * @param overdue the part of {@code outstanding} whose due date plus grace has passed
   */
  public record CollectionsPosition(
      LocalDate from,
      LocalDate to,
      BigDecimal invoiced,
      BigDecimal collected,
      BigDecimal outstanding,
      BigDecimal overdue,
      long overdueCount) {}

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /**
   * One line to bill.
   *
   * <p>{@code unitAmount} is deliberately allowed to be negative - a credit line - and its tax is
   * computed server-side. {@code subtotal} on the parent request is optional: when a caller states
   * one it must equal the sum of the lines, otherwise the lines are the authority.</p>
   */
  public record InvoiceLineRequest(
      @NotBlank(message = "A line type is required")
          @Pattern(
              regexp = "RENT|MAINTENANCE|UTILITY|PARKING|LATE_FEE|ADJUSTMENT|TAX|OTHER",
              message = "must be a line type")
          String lineType,
      @NotBlank(message = "A description is required") @Size(max = 255) String description,
      @DecimalMin(value = "0.01", message = "must be greater than zero") BigDecimal quantity,
      @NotNull(message = "A unit amount is required") BigDecimal unitAmount,
      @DecimalMin(value = "0.0", message = "must not be negative")
          @DecimalMax(value = "100.0", message = "must not exceed 100")
          BigDecimal taxRatePercent,
      Integer displayOrder) {}

  /** Raise an invoice for a lease and a period. */
  public record IssueInvoiceRequest(
      @NotNull(message = "A period start is required") LocalDate periodStart,
      @NotNull(message = "A period end is required") LocalDate periodEnd,
      LocalDate dueDate,
      @DecimalMin(value = "0.0", message = "must not be negative") BigDecimal subtotal,
      @Size(max = 1000) String notes,
      @NotEmpty(message = "An invoice needs at least one line")
          List<@Valid InvoiceLineRequest> lines) {}

  /** Void an issued invoice. Its allocations stop counting toward settlement. */
  public record VoidInvoiceRequest(
      @NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

  /** Write an invoice off as uncollectable, with the reason recorded. */
  public record WriteOffInvoiceRequest(
      @NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}
}
