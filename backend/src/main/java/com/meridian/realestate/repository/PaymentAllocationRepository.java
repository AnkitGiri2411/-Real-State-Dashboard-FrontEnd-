package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PaymentAllocation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The allocations that make an invoice's settlement derivable.
 *
 * <p>The two sum queries here are what the service compares against the caps the schema does not
 * enforce: {@code sumAllocatedForInvoice} against the invoice's {@code total_amount}, and
 * {@code sumAllocatedForPayment} against the receipt's {@code amount}. Both are taken inside one
 * transaction, because between reading a sum and writing a row another allocation could land.</p>
 */
public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {

  Optional<PaymentAllocation> findByIdAndDeletedAtIsNull(UUID id);

  Page<PaymentAllocation> findAllByDeletedAtIsNull(Pageable pageable);

  /**
   * The allocations on one invoice, with the receipt fetched.
   *
   * <p>The fetch join is the point: an invoice's rows each need their payment's status to decide
   * whether they count as settled, and without fetching that here the invoice list becomes an N+1.
   * Fetching a to-one cannot duplicate rows.</p>
   */
  @Query(
      "select a from PaymentAllocation a join fetch a.payment p "
          + "where a.deletedAt is null and a.invoice.id = :invoiceId")
  List<PaymentAllocation> findAllByInvoiceIdFetchPayment(@Param("invoiceId") UUID invoiceId);

  /** The invoices one receipt has been applied to. */
  List<PaymentAllocation> findAllByPayment_IdAndDeletedAtIsNull(UUID paymentId);

  /**
   * The total allocated to one invoice, across every live allocation.
   *
   * <p>Deliberately does not filter by payment status: the cap is on what has been <em>posted</em>
   * against the invoice. The service compares this against {@code rent_invoices.total_amount} and
   * must refuse an allocation that would exceed it.</p>
   */
  @Query(
      "select coalesce(sum(a.amount), 0) from PaymentAllocation a "
          + "where a.deletedAt is null and a.invoice.id = :invoiceId")
  BigDecimal sumAllocatedForInvoice(@Param("invoiceId") UUID invoiceId);

  /**
   * The total of one receipt already allocated to invoices.
   *
   * <p>The service compares this against {@code payments.amount} and must refuse an allocation that
   * would exceed it. Like the invoice cap, it ignores payment status - it is the posted total.</p>
   */
  @Query(
      "select coalesce(sum(a.amount), 0) from PaymentAllocation a "
          + "where a.deletedAt is null and a.payment.id = :paymentId")
  BigDecimal sumAllocatedForPayment(@Param("paymentId") UUID paymentId);
}
