package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The join that makes an invoice's settlement derivable: which receipt settled which invoice.
 *
 * <p>Tables {@code payment_allocations}. One receipt may settle several invoices and one invoice may
 * be settled by several receipts, which is ordinary - a tenant pays two months at once, or clears an
 * arrears balance in instalments. A {@code VOID} invoice's allocations do not count toward
 * settlement: the allocations row still exists, but the derivation excludes the invoice.</p>
 *
 * <p><b>Over-allocation is NOT prevented by the database.</b> The schema deliberately does not carry
 * these, because each spans multiple rows and cannot be a row-level CHECK. The <b>service must
 * enforce them inside one transaction</b>:</p>
 * <ul>
 *   <li>{@code SUM(payment_allocations.amount)} for an invoice may not exceed
 *       {@code rent_invoices.total_amount};</li>
 *   <li>{@code SUM(payment_allocations.amount)} for a receipt may not exceed
 *       {@code payments.amount};</li>
 *   <li>the total refunded against a receipt may not exceed that receipt;</li>
 *   <li>{@code SUM(rent_invoice_lines.line_total)} must equal {@code rent_invoices.subtotal};</li>
 *   <li>a {@code VOID} invoice's allocations must not count toward settlement.</li>
 * </ul>
 *
 * <p>{@code uq_payment_allocations_pair} allows exactly one live row per {@code (payment, invoice)}
 * pair: a second row for the same pair is a correction (an update) or a duplicate, and duplicates
 * are how an invoice gets settled twice from one payment.</p>
 *
 * <p><b>Money.</b> {@code amount} is {@code numeric(14, 2)} and is constrained {@code > 0} by
 * {@code ck_payment_allocations_amount_positive}; it is an absolute amount, mapped as
 * {@link BigDecimal} at scale 2. Decimal arithmetic happens in the service layer in
 * {@link BigDecimal}, HALF_UP.</p>
 *
 * <p>Soft delete, hence {@link Auditable}.</p>
 */
@Entity
@Table(name = "payment_allocations")
public class PaymentAllocation extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payment_id", nullable = false)
  private Payment payment;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "invoice_id", nullable = false)
  private RentInvoice invoice;

  @Column(name = "amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(name = "allocated_at", nullable = false)
  private LocalDateTime allocatedAt;

  /** Raw identifier - the users slice is not part of this module. */
  @Column(name = "allocated_by")
  private UUID allocatedBy;

  @Column(name = "notes", length = 500)
  private String notes;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Payment getPayment() {
    return payment;
  }

  public void setPayment(Payment payment) {
    this.payment = payment;
  }

  public RentInvoice getInvoice() {
    return invoice;
  }

  public void setInvoice(RentInvoice invoice) {
    this.invoice = invoice;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public LocalDateTime getAllocatedAt() {
    return allocatedAt;
  }

  public void setAllocatedAt(LocalDateTime allocatedAt) {
    this.allocatedAt = allocatedAt;
  }

  public UUID getAllocatedBy() {
    return allocatedBy;
  }

  public void setAllocatedBy(UUID allocatedBy) {
    this.allocatedBy = allocatedBy;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
