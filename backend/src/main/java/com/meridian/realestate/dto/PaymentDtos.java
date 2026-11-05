package com.meridian.realestate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The payment ledger's contracts: receipts, the outbound rows that reverse them, allocations and
 * the collections summary.
 *
 * <h2>A refund is a row, not a status</h2>
 * <p>{@code direction} is {@code INBOUND} or {@code OUTBOUND}. A refund is a separate
 * {@code OUTBOUND} row whose {@code refundOfPaymentId} names the receipt it reverses; there is
 * deliberately no {@code REFUNDED} status, because a status cannot express a <em>partial</em>
 * refund and partial refunds are ordinary. That is why {@code collected} is a signed sum over
 * rows rather than a naive {@code SUM(amount)}.</p>
 *
 * <p><b>Money is {@link BigDecimal} only.</b> {@code payments.amount} is always positive - a
 * refund's magnitude is positive too, and its direction is what makes it a refund - and no field
 * here is a {@code double} or a {@code float}.</p>
 */
public final class PaymentDtos {

  private PaymentDtos() {}

  // ---------------------------------------------------------------------------
  // Read models
  // ---------------------------------------------------------------------------

  /** A row in the payment ledger. */
  public record PaymentSummary(
      UUID id,
      String receiptNumber,
      String direction,
      UUID refundOfPaymentId,
      String status,
      BigDecimal amount,
      String currency,
      UUID paymentMethodId,
      String paymentMethodCode,
      String paymentMethodName,
      UUID tenantId,
      UUID buyerId,
      UUID leaseId,
      UUID saleId,
      UUID propertyId,
      LocalDateTime receivedAt,
      String reference,
      String notes,
      UUID recordedBy,
      LocalDateTime voidedAt,
      UUID voidedBy,
      String voidReason,
      LocalDateTime createdAt) {}

  /** One invoice a receipt has been applied to. */
  public record AllocationEntry(
      UUID id,
      UUID invoiceId,
      String invoiceNumber,
      BigDecimal amount,
      LocalDateTime allocatedAt,
      UUID allocatedBy) {}

  /**
   * A receipt with its allocations and how much of it is still unapplied.
   *
   * <p>{@code unallocated} is {@code amount - allocated}: money received that has not yet been put
   * against an invoice, which the desk can see and apply. It is derived, never stored.</p>
   */
  public record PaymentDetail(
      PaymentSummary payment,
      List<AllocationEntry> allocations,
      BigDecimal allocated,
      BigDecimal unallocated) {}

  /**
   * The firm's collections over a period.
   *
   * @param collected receipts that cleared ({@code status = 'PAID'} and {@code INBOUND})
   * @param refunded refunds that cleared ({@code status = 'PAID'} and {@code OUTBOUND})
   * @param netCollected {@code collected - refunded}
   */
  public record CollectionsSummary(
      LocalDate from,
      LocalDate to,
      BigDecimal collected,
      BigDecimal refunded,
      BigDecimal netCollected,
      long receiptCount,
      long refundCount) {}

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /**
   * Record a receipt.
   *
   * <p>Whom the money came from is named explicitly. A receipt settles <b>either</b> rent (through
   * {@code leaseId}) <b>or</b> consideration on a sale (through {@code saleId}) - never both and
   * never neither - which the service checks before the row is written. The currency defaults to
   * the installation's own (INR).</p>
   */
  public record RecordPaymentRequest(
      @NotNull(message = "A payment method is required") UUID paymentMethodId,
      @NotNull(message = "An amount is required")
          @DecimalMin(value = "0.01", message = "must be greater than zero")
          BigDecimal amount,
      String direction,
      String status,
      String currency,
      UUID tenantId,
      UUID buyerId,
      UUID leaseId,
      UUID saleId,
      UUID propertyId,
      LocalDateTime receivedAt,
      @Size(max = 120) String reference,
      @Size(max = 1000) String notes) {}

  /** Void a receipt. The row survives and stops counting toward settlement. */
  public record VoidPaymentRequest(
      @NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

  /**
   * Refund a receipt, in whole or in part.
   *
   * <p>Recorded as a new {@code OUTBOUND} row rather than by editing the original, so a partial
   * refund is representable and the original receipt stays intact.</p>
   */
  public record RefundRequest(
      @NotNull(message = "The receipt being refunded is required") UUID refundOfPaymentId,
      @NotNull(message = "An amount is required")
          @DecimalMin(value = "0.01", message = "must be greater than zero")
          BigDecimal amount,
      @NotNull(message = "A payment method is required") UUID paymentMethodId,
      @Size(max = 120) String reference,
      @Size(max = 1000) String notes,
      LocalDateTime receivedAt) {}

  /** Apply one or more allocations of a receipt to invoices. */
  public record AllocationRequest(
      @NotEmpty(message = "At least one allocation is required")
          List<@Valid AllocationLine> allocations) {}

  /** One invoice a receipt is applied to. */
  public record AllocationLine(
      @NotNull(message = "An invoice is required") UUID invoiceId,
      @NotNull(message = "An amount is required")
          @DecimalMin(value = "0.01", message = "must be greater than zero")
          BigDecimal amount,
      @Size(max = 500) String notes) {}
}
