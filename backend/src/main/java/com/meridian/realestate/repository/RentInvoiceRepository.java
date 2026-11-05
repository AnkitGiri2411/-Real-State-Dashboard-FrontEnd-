package com.meridian.realestate.repository;

import com.meridian.realestate.domain.InvoiceStatus;
import com.meridian.realestate.domain.RentInvoice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The rent invoices, and the settlement figures derived from the allocations posted against them.
 *
 * <p>Nothing here reads a stored "paid" amount - there is none. The two aggregate queries below
 * derive settlement from {@code payment_allocations} joined to {@code payments} filtered to
 * {@code status = 'PAID'}, which is the one place the ledger rule is expressed in SQL so the
 * service and the report cannot disagree.</p>
 *
 * <p>No paged query carries a literal {@code order by}; sorting arrives as a {@link Pageable}. Every
 * read filters on {@code deleted_at is null}.</p>
 */
public interface RentInvoiceRepository extends JpaRepository<RentInvoice, UUID> {

  Optional<RentInvoice> findByIdAndDeletedAtIsNull(UUID id);

  Optional<RentInvoice> findByInvoiceNumberIgnoreCaseAndDeletedAtIsNull(String invoiceNumber);

  boolean existsByInvoiceNumberIgnoreCaseAndDeletedAtIsNull(String invoiceNumber);

  Page<RentInvoice> findAllByDeletedAtIsNull(Pageable pageable);

  /** The invoices raised against one lease. */
  Page<RentInvoice> findAllByLease_IdAndDeletedAtIsNull(UUID leaseId, Pageable pageable);

  /** The invoices raised against one tenant. */
  Page<RentInvoice> findAllByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);

  /** Invoices in one stored state. */
  Page<RentInvoice> findAllByStatusAndDeletedAtIsNull(InvoiceStatus status, Pageable pageable);

  long countByStatusAndDeletedAtIsNull(InvoiceStatus status);

  /**
   * Invoices past their due date and still collectable - status {@code ISSUED}, due before the
   * given day.
   *
   * <p>"Past due" here is the due date, not overdue: whether an invoice is reported <em>overdue</em>
   * also depends on the configured grace period
   * ({@code app.portfolio.rent-overdue-grace-days}), which the service applies on top of this set.
   * A {@code VOID} or {@code WRITTEN_OFF} invoice is not collectable and is not returned.</p>
   */
  @Query(
      "select i from RentInvoice i "
          + "where i.deletedAt is null "
          + "and i.status = com.meridian.realestate.domain.InvoiceStatus.ISSUED "
          + "and i.dueDate < :asOf")
  Page<RentInvoice> findPastDueCollectable(@Param("asOf") LocalDate asOf, Pageable pageable);

  /**
   * The collections position over a period: total invoiced, total settled and total outstanding.
   *
   * <p>Settlement is summed from {@code payment_allocations} joined to {@code payments} filtered to
   * {@code status = 'PAID'} - a pending, failed or voided receipt is not cash. Voided invoices are
   * excluded from every figure, so a cancelled invoice's allocations are never counted. The period
   * is read on {@code period_start}, matching the trend below.</p>
   *
   * <p>{@code outstanding} is {@code sum(total_amount - credited_amount) - settled}, i.e. the
   * stored {@code total_amount - credited_amount - settled} rolled up over the period.</p>
   */
  @Query(
      "select coalesce(sum(i.totalAmount), 0) as invoiced, "
          + "coalesce(sum((select coalesce(sum(a.amount), 0) "
          + "  from PaymentAllocation a join a.payment p "
          + "  where a.invoice = i and a.deletedAt is null and p.deletedAt is null "
          + "  and p.status = com.meridian.realestate.domain.PaymentStatus.PAID)), 0) as settled, "
          + "coalesce(sum(i.totalAmount - i.creditedAmount), 0) "
          + "  - coalesce(sum((select coalesce(sum(a.amount), 0) "
          + "    from PaymentAllocation a join a.payment p "
          + "    where a.invoice = i and a.deletedAt is null and p.deletedAt is null "
          + "    and p.status = com.meridian.realestate.domain.PaymentStatus.PAID)), 0) "
          + "  as outstanding "
          + "from RentInvoice i "
          + "where i.deletedAt is null "
          + "and i.status <> com.meridian.realestate.domain.InvoiceStatus.VOID "
          + "and i.periodStart between :from and :to")
  CollectionsPosition collectionsPosition(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /**
   * The settlement position of one invoice, as a projection the service can reuse.
   *
   * <p>The same derivation as {@link #collectionsPosition}, for a single invoice: {@code settled} is
   * the sum of allocations on {@code PAID} payments, and {@code outstanding} is
   * {@code total_amount - credited_amount - settled}. Note this does not subtract void status -
   * a void invoice's own figures are still returned, and the service decides whether they count.</p>
   */
  @Query(
      "select i.id as invoiceId, "
          + "i.totalAmount as totalAmount, "
          + "i.creditedAmount as creditedAmount, "
          + "coalesce((select coalesce(sum(a.amount), 0) "
          + "  from PaymentAllocation a join a.payment p "
          + "  where a.invoice = i and a.deletedAt is null and p.deletedAt is null "
          + "  and p.status = com.meridian.realestate.domain.PaymentStatus.PAID), 0) as settled, "
          + "i.totalAmount - i.creditedAmount "
          + "  - coalesce((select coalesce(sum(a.amount), 0) "
          + "    from PaymentAllocation a join a.payment p "
          + "    where a.invoice = i and a.deletedAt is null and p.deletedAt is null "
          + "    and p.status = com.meridian.realestate.domain.PaymentStatus.PAID), 0) "
          + "  as outstanding "
          + "from RentInvoice i "
          + "where i.deletedAt is null and i.id = :invoiceId")
  Optional<InvoiceOutstanding> findOutstandingByInvoiceId(@Param("invoiceId") UUID invoiceId);

  /**
   * The monthly invoiced-versus-collected trend.
   *
   * <p>Invoiced is the month's issued totals; collected is what allocations on {@code PAID}
   * receipts settled against those same invoices. Grouped and ordered by {@code period_start}'s
   * year and month - a sequence, so the ordering is part of the answer and is written here rather
   * than left to a caller.</p>
   */
  @Query(
      "select year(i.periodStart) as periodYear, "
          + "month(i.periodStart) as periodMonth, "
          + "coalesce(sum(i.totalAmount), 0) as invoiced, "
          + "coalesce(sum((select coalesce(sum(a.amount), 0) "
          + "  from PaymentAllocation a join a.payment p "
          + "  where a.invoice = i and a.deletedAt is null and p.deletedAt is null "
          + "  and p.status = com.meridian.realestate.domain.PaymentStatus.PAID)), 0) as collected "
          + "from RentInvoice i "
          + "where i.deletedAt is null "
          + "and i.status <> com.meridian.realestate.domain.InvoiceStatus.VOID "
          + "and i.periodStart between :from and :to "
          + "group by year(i.periodStart), month(i.periodStart) "
          + "order by year(i.periodStart), month(i.periodStart)")
  java.util.List<MonthlyInvoicedVersusCollected> monthlyInvoicedVersusCollected(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** Total invoiced, total settled and total outstanding over a period. */
  interface CollectionsPosition {

    BigDecimal getInvoiced();

    BigDecimal getSettled();

    BigDecimal getOutstanding();
  }

  /** The settlement position of a single invoice, reusable by the service. */
  interface InvoiceOutstanding {

    UUID getInvoiceId();

    BigDecimal getTotalAmount();

    BigDecimal getCreditedAmount();

    BigDecimal getSettled();

    BigDecimal getOutstanding();
  }

  /** One month of the invoiced-versus-collected trend. */
  interface MonthlyInvoicedVersusCollected {

    int getPeriodYear();

    int getPeriodMonth();

    BigDecimal getInvoiced();

    BigDecimal getCollected();
  }
}
