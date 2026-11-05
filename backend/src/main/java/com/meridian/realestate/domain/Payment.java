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
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A receipt, and the outbound rows that reverse one.
 *
 * <p>Tables {@code payments}. This is the row that says money actually moved; an invoice's
 * settlement is only as true as the {@link PaymentAllocation}s pointing at payments in here.</p>
 *
 * <h2>A refund is a row, not a status</h2>
 *
 * <p>{@link #direction} is {@link PaymentDirection}. {@code INBOUND} is money received; a refund is
 * a separate {@code OUTBOUND} row whose {@link #refundOfPayment} points at the receipt it reverses.
 * Voiding sets {@link #voidedAt} and leaves the row; a refund adds a row. Nothing is ever deleted.
 * That is what makes a <em>partial</em> refund representable - a status could not tell a partial
 * refund from a full one.</p>
 *
 * <h2>Collected is a signed sum, not a naive SUM(amount)</h2>
 *
 * <p>{@code payments.amount} is {@code numeric(14, 2)} and is constrained {@code > 0} by
 * {@code ck_payments_amount_positive}: it is an absolute amount, never a negative. A refund row is
 * therefore the <em>same</em> positive magnitude, so a naive {@code SUM(amount)} counts refunds as
 * collections and overstates the book. Correct collections require <b>both</b> filters:</p>
 * <ul>
 *   <li>{@code status = 'PAID'} - only cleared money is cash; and</li>
 *   <li>signing by {@code direction} - add {@code INBOUND}, subtract {@code OUTBOUND}
 *       ({@link PaymentDirection#sign()}).</li>
 * </ul>
 *
 * <h2>What a receipt settles</h2>
 *
 * <p>{@code ck_payments_settles_one_thing} means a receipt settles <b>either</b> rent (through
 * {@link #lease}) <b>or</b> consideration on a sale (through {@link #saleId}) - never both and never
 * neither. {@code sale_id} was added by the V16 migration, which creates {@code sales_opportunities},
 * and is mapped here as a raw identifier because the sales slice is not part of this module.</p>
 *
 * <h2>Currency</h2>
 *
 * <p>{@link #currency} is {@code varchar(3)}, defaulting to {@code 'INR'}, and is the <b>only</b>
 * currency column in the entire schema. Everything else - invoices, allocation amounts, the rent
 * roll - is implicitly single-currency and carries no code of its own; a report must not present
 * those figures as though each had been converted.</p>
 *
 * <h2>Money</h2>
 *
 * <p>{@code amount} is {@code numeric(14, 2)}, mapped as {@link BigDecimal} at scale 2. Decimal
 * arithmetic happens in the service layer in {@link BigDecimal}, HALF_UP. Nothing here is a
 * {@code double} or a {@code float}.</p>
 *
 * <p>Soft delete, hence {@link Auditable}. {@code tenant_id}, {@code buyer_id} and {@code sale_id}
 * are raw identifiers because those slices are not part of this module; {@code lease_id} and
 * {@code property_id} are associations.</p>
 */
@Entity
@Table(name = "payments")
public class Payment extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "receipt_number", nullable = false, length = 30, unique = true)
  private String receiptNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "direction", nullable = false, length = 10)
  private PaymentDirection direction = PaymentDirection.INBOUND;

  /** The receipt this row refunds. Set only on an {@code OUTBOUND} row. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "refund_of_payment_id")
  private Payment refundOfPayment;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payment_method_id", nullable = false)
  private PaymentMethod paymentMethod;

  /** Whom the money came from, when it is rent. Raw identifier - no tenant entity in this slice. */
  @Column(name = "tenant_id")
  private UUID tenantId;

  /** Whom the money came from, when it is sale consideration. Raw identifier. */
  @Column(name = "buyer_id")
  private UUID buyerId;

  /** The lease this receipt settles, when it settles rent. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lease_id")
  private Lease lease;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "property_id")
  private Property property;

  /**
   * The sale this receipt settles, when it is sale consideration. Added by V16; raw identifier
   * because the sales slice is not part of this module. Mutually exclusive with {@link #lease}
   * under {@code ck_payments_settles_one_thing}.
   */
  @Column(name = "sale_id")
  private UUID saleId;

  @Column(name = "amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  /** The only currency column in the schema. {@code varchar(3)}, default {@code 'INR'}. */
  @Column(name = "currency", nullable = false, length = 3)
  private String currency = "INR";

  @Column(name = "received_at", nullable = false)
  private LocalDateTime receivedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PaymentStatus status = PaymentStatus.PENDING;

  @Column(name = "reference", length = 120)
  private String reference;

  @Column(name = "notes", length = 1000)
  private String notes;

  /** Set when the receipt is withdrawn; the row survives. */
  @Column(name = "voided_at")
  private LocalDateTime voidedAt;

  /** Raw identifier - the users slice is not part of this module. */
  @Column(name = "voided_by")
  private UUID voidedBy;

  @Column(name = "void_reason", length = 500)
  private String voidReason;

  @Column(name = "failed_reason", length = 500)
  private String failedReason;

  /** Raw identifier - the users slice is not part of this module. */
  @Column(name = "recorded_by")
  private UUID recordedBy;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getReceiptNumber() {
    return receiptNumber;
  }

  public void setReceiptNumber(String receiptNumber) {
    this.receiptNumber = receiptNumber;
  }

  public PaymentDirection getDirection() {
    return direction;
  }

  public void setDirection(PaymentDirection direction) {
    this.direction = direction;
  }

  public Payment getRefundOfPayment() {
    return refundOfPayment;
  }

  public void setRefundOfPayment(Payment refundOfPayment) {
    this.refundOfPayment = refundOfPayment;
  }

  public PaymentMethod getPaymentMethod() {
    return paymentMethod;
  }

  public void setPaymentMethod(PaymentMethod paymentMethod) {
    this.paymentMethod = paymentMethod;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public void setBuyerId(UUID buyerId) {
    this.buyerId = buyerId;
  }

  public Lease getLease() {
    return lease;
  }

  public void setLease(Lease lease) {
    this.lease = lease;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public UUID getSaleId() {
    return saleId;
  }

  public void setSaleId(UUID saleId) {
    this.saleId = saleId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public LocalDateTime getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(LocalDateTime receivedAt) {
    this.receivedAt = receivedAt;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public void setStatus(PaymentStatus status) {
    this.status = status;
  }

  public String getReference() {
    return reference;
  }

  public void setReference(String reference) {
    this.reference = reference;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
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

  public String getFailedReason() {
    return failedReason;
  }

  public void setFailedReason(String failedReason) {
    this.failedReason = failedReason;
  }

  public UUID getRecordedBy() {
    return recordedBy;
  }

  public void setRecordedBy(UUID recordedBy) {
    this.recordedBy = recordedBy;
  }
}
