package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A demand for rent for one period - a document that was issued with figures on it.
 *
 * <p>Tables {@code rent_invoices}, the header of the rent ledger.</p>
 *
 * <h2>What is stored, and what is derived</h2>
 *
 * <p>{@code subtotal}, {@code tax_amount}, {@code total_amount} and {@code credited_amount} are
 * stored because the invoice is a document: they are facts about what was put in front of the
 * tenant, not a running total. Everything about whether it has been <em>paid</em> is deliberately
 * not stored.</p>
 *
 * <p>{@link #status} is {@link InvoiceStatus} - {@code DRAFT | ISSUED | VOID | WRITTEN_OFF} only.
 * There is <b>no</b> {@code PAID}, {@code PARTIALLY_PAID} or {@code OVERDUE} member, because those
 * are functions of the allocations posted against the invoice and storing one would create a value
 * that can silently disagree with the ledger it summarises. Settlement is derived in the service
 * layer, exactly as the V13 migration header states:</p>
 *
 * <pre>
 *   settled     = SUM(allocation.amount) over non-voided, PAID payments
 *   outstanding = total_amount - credited_amount - settled
 *   overdue     = outstanding &gt; 0 AND due_date + grace &lt; today
 * </pre>
 *
 * <p>The grace period is configuration ({@code app.portfolio.rent-overdue-grace-days}), <b>not</b>
 * a column: two firms disagree about it, and a leave-behind in the row would freeze it into data.
 * There is no {@code amount_paid} column here and no {@code @Formula}, generated column, view or
 * trigger behind this entity - the migration chain has none by design, and adding one here would
 * create a second source of financial truth.</p>
 *
 * <h2>Invariants the SERVICE must enforce, in one transaction</h2>
 *
 * <p>The schema does not enforce these, because they span rows; the service must:</p>
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
 * <h2>One invoice per lease per period</h2>
 *
 * <p>The partial unique index {@code uq_rent_invoices_lease_period} on
 * {@code (lease_id, period_start, period_end) WHERE deleted_at IS NULL} means the same lease cannot
 * be billed twice for one month. This is what makes invoice generation idempotent at the
 * database.</p>
 *
 * <p><b>Money.</b> {@code subtotal}, {@code tax_amount}, {@code total_amount} and
 * {@code credited_amount} are {@code numeric(14, 2)}, mapped as {@link BigDecimal} at scale 2.
 * Decimal arithmetic happens in the service layer in {@link BigDecimal}, HALF_UP.</p>
 *
 * <p>Soft delete, hence {@link Auditable}. {@code tenant_id} and {@code unit_id} are raw identifiers
 * because those slices are not part of this module; {@code property_id} is an association because
 * the asset register already exists here.</p>
 */
@Entity
@Table(name = "rent_invoices")
public class RentInvoice extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "invoice_number", nullable = false, length = 30, unique = true)
  private String invoiceNumber;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lease_id", nullable = false)
  private Lease lease;

  /** The party invoiced. Raw identifier - the tenants slice is not part of this module. */
  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /** Raw identifier - the units slice is not part of this module. */
  @Column(name = "unit_id", nullable = false)
  private UUID unitId;

  /** The period charged for; distinct from the due date. */
  @Column(name = "period_start", nullable = false)
  private LocalDate periodStart;

  @Column(name = "period_end", nullable = false)
  private LocalDate periodEnd;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
  private BigDecimal subtotal;

  @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal taxAmount = BigDecimal.ZERO;

  @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal totalAmount;

  /** A write-off or goodwill credit - reduces what is owed without being cash. */
  @Column(name = "credited_amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal creditedAmount = BigDecimal.ZERO;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private InvoiceStatus status = InvoiceStatus.DRAFT;

  @Column(name = "issued_at")
  private LocalDateTime issuedAt;

  @Column(name = "voided_at")
  private LocalDateTime voidedAt;

  /** Raw identifier - the users slice is not part of this module. */
  @Column(name = "voided_by")
  private UUID voidedBy;

  @Column(name = "void_reason", length = 500)
  private String voidReason;

  @Column(name = "written_off_at")
  private LocalDateTime writtenOffAt;

  /** Raw identifier - the users slice is not part of this module. */
  @Column(name = "written_off_by")
  private UUID writtenOffBy;

  @Column(name = "write_off_reason", length = 500)
  private String writeOffReason;

  @Column(name = "notes", length = 1000)
  private String notes;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public void setInvoiceNumber(String invoiceNumber) {
    this.invoiceNumber = invoiceNumber;
  }

  public Lease getLease() {
    return lease;
  }

  public void setLease(Lease lease) {
    this.lease = lease;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public UUID getUnitId() {
    return unitId;
  }

  public void setUnitId(UUID unitId) {
    this.unitId = unitId;
  }

  public LocalDate getPeriodStart() {
    return periodStart;
  }

  public void setPeriodStart(LocalDate periodStart) {
    this.periodStart = periodStart;
  }

  public LocalDate getPeriodEnd() {
    return periodEnd;
  }

  public void setPeriodEnd(LocalDate periodEnd) {
    this.periodEnd = periodEnd;
  }

  public LocalDate getDueDate() {
    return dueDate;
  }

  public void setDueDate(LocalDate dueDate) {
    this.dueDate = dueDate;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public void setSubtotal(BigDecimal subtotal) {
    this.subtotal = subtotal;
  }

  public BigDecimal getTaxAmount() {
    return taxAmount;
  }

  public void setTaxAmount(BigDecimal taxAmount) {
    this.taxAmount = taxAmount;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
  }

  public BigDecimal getCreditedAmount() {
    return creditedAmount;
  }

  public void setCreditedAmount(BigDecimal creditedAmount) {
    this.creditedAmount = creditedAmount;
  }

  public InvoiceStatus getStatus() {
    return status;
  }

  public void setStatus(InvoiceStatus status) {
    this.status = status;
  }

  public LocalDateTime getIssuedAt() {
    return issuedAt;
  }

  public void setIssuedAt(LocalDateTime issuedAt) {
    this.issuedAt = issuedAt;
  }

  public LocalDateTime getVoidedAt() {
    return voidedAt;
  }

  public void setVoidedAt(LocalDateTime voidedAt) {
    this.voidedAt = voidedAt;
  }

  public UUID getVoidedBy() {
    return voidedBy;
  }

  public void setVoidedBy(UUID voidedBy) {
    this.voidedBy = voidedBy;
  }

  public String getVoidReason() {
    return voidReason;
  }

  public void setVoidReason(String voidReason) {
    this.voidReason = voidReason;
  }

  public LocalDateTime getWrittenOffAt() {
    return writtenOffAt;
  }

  public void setWrittenOffAt(LocalDateTime writtenOffAt) {
    this.writtenOffAt = writtenOffAt;
  }

  public UUID getWrittenOffBy() {
    return writtenOffBy;
  }

  public void setWrittenOffBy(UUID writtenOffBy) {
    this.writtenOffBy = writtenOffBy;
  }

  public String getWriteOffReason() {
    return writeOffReason;
  }

  public void setWriteOffReason(String writeOffReason) {
    this.writeOffReason = writeOffReason;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
