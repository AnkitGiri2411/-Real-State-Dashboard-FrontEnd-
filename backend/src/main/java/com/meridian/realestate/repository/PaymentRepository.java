package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.PaymentDirection;
import com.meridian.realestate.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The payment ledger - receipts, and the outbound rows that reverse them.
 *
 * <p>No paged query carries a literal {@code order by}; sorting arrives as a {@link Pageable}. Every
 * read filters on {@code deleted_at is null}. A receipt is never counted as collected unless its
 * {@code status} is {@code PAID}.</p>
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  Optional<Payment> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Payment> findByReceiptNumberIgnoreCaseAndDeletedAtIsNull(String receiptNumber);

  boolean existsByReceiptNumberIgnoreCaseAndDeletedAtIsNull(String receiptNumber);

  Page<Payment> findAllByDeletedAtIsNull(Pageable pageable);

  /** Receipts settled against one lease. */
  Page<Payment> findAllByLease_IdAndDeletedAtIsNull(UUID leaseId, Pageable pageable);

  /** Receipts from one tenant. */
  Page<Payment> findAllByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);

  /** Receipts from one buyer. */
  Page<Payment> findAllByBuyerIdAndDeletedAtIsNull(UUID buyerId, Pageable pageable);

  /** Receipts posted against one sale, as raw identifiers - the sales slice is not in this module. */
  Page<Payment> findAllBySaleIdAndDeletedAtIsNull(UUID saleId, Pageable pageable);

  Page<Payment> findAllByStatusAndDeletedAtIsNull(PaymentStatus status, Pageable pageable);

  Page<Payment> findAllByDirectionAndDeletedAtIsNull(
      PaymentDirection direction, Pageable pageable);

  long countByStatusAndDeletedAtIsNull(PaymentStatus status);

  /**
   * Cash actually collected over a date range.
   *
   * <p>Honours <b>both</b> ledger rules: {@code status = 'PAID'} so only cleared money counts, and
   * {@code direction = 'INBOUND'} so a refund - an {@code OUTBOUND} row of the same positive
   * magnitude - is not added back as if it were a collection. A naive {@code SUM(amount)} over the
   * table would overstate this figure; that is exactly the mistake this query exists to prevent.</p>
   */
  @Query(
      "select coalesce(sum(p.amount), 0) from Payment p "
          + "where p.deletedAt is null "
          + "and p.status = com.meridian.realestate.domain.PaymentStatus.PAID "
          + "and p.direction = com.meridian.realestate.domain.PaymentDirection.INBOUND "
          + "and p.receivedAt between :from and :to")
  BigDecimal sumCollectedBetween(
      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  /**
   * How many live refund rows reverse one receipt.
   *
   * <p>Part of the refund cap: the service compares this (and the summed amount below) against the
   * original receipt before posting another refund. A voided refund row is not live and is not
   * counted.</p>
   */
  long countByRefundOfPayment_IdAndDeletedAtIsNull(UUID paymentId);

  /**
   * The total refunded against one receipt, over non-voided outbound rows.
   *
   * <p>This is the amount the service compares against {@code payments.amount} for the original
   * receipt - the schema spans rows here, so the cap is enforced in the service, in one
   * transaction.</p>
   */
  @Query(
      "select coalesce(sum(p.amount), 0) from Payment p "
          + "where p.deletedAt is null "
          + "and p.direction = com.meridian.realestate.domain.PaymentDirection.OUTBOUND "
          + "and p.status <> com.meridian.realestate.domain.PaymentStatus.VOIDED "
          + "and p.refundOfPayment.id = :paymentId")
  BigDecimal sumRefundedForPayment(@Param("paymentId") UUID paymentId);
}
