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
import java.util.UUID;

/**
 * One line of a rent invoice - the rent itself, a maintenance passthrough, a late fee, and so on.
 *
 * <p>Tables {@code rent_invoice_lines}. The rule that the invoice's stored {@code subtotal} equals
 * {@code SUM(line_total)} spans rows and is enforced by the service inside one transaction, not by
 * the schema.</p>
 *
 * <p><b>A line may legitimately be negative.</b> {@code unit_amount} and {@code line_total} are
 * <em>deliberately</em> allowed below zero: a credit line - a goodwill adjustment, a
 * {@link RentInvoiceLineType#ADJUSTMENT} - is a real thing and is modelled as a line rather than as
 * a payment. Nothing here assumes positivity, and no Javadoc or validation should introduce that
 * assumption. The only guard on a line's magnitude is {@code quantity > 0} and
 * {@code tax_amount >= 0}.</p>
 *
 * <p><b>Money.</b> {@code quantity} is {@code numeric(10, 2)}, {@code unit_amount} is
 * {@code numeric(12, 2)}, {@code line_total} and {@code tax_amount} are {@code numeric(14, 2)}, and
 * {@code tax_rate_percent} is {@code numeric(5, 2)} - each mapped as {@link BigDecimal} at scale 2.
 * Decimal arithmetic happens in the service layer in {@link BigDecimal}, HALF_UP.</p>
 *
 * <p>Soft delete, hence {@link Auditable}.</p>
 */
@Entity
@Table(name = "rent_invoice_lines")
public class RentInvoiceLine extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "invoice_id", nullable = false)
  private RentInvoice invoice;

  @Enumerated(EnumType.STRING)
  @Column(name = "line_type", nullable = false, length = 30)
  private RentInvoiceLineType lineType;

  @Column(name = "description", nullable = false, length = 255)
  private String description;

  @Column(name = "quantity", nullable = false, precision = 10, scale = 2)
  private BigDecimal quantity = BigDecimal.ONE;

  /** The per-unit price. May be negative: a credit line is not an error. */
  @Column(name = "unit_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal unitAmount = BigDecimal.ZERO;

  /** The line total. May be negative, for the same reason {@link #unitAmount} may be. */
  @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
  private BigDecimal lineTotal;

  @Column(name = "tax_rate_percent", nullable = false, precision = 5, scale = 2)
  private BigDecimal taxRatePercent = BigDecimal.ZERO;

  @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal taxAmount = BigDecimal.ZERO;

  @Column(name = "display_order", nullable = false)
  private int displayOrder = 0;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public RentInvoice getInvoice() {
    return invoice;
  }

  public void setInvoice(RentInvoice invoice) {
    this.invoice = invoice;
  }

  public RentInvoiceLineType getLineType() {
    return lineType;
  }

  public void setLineType(RentInvoiceLineType lineType) {
    this.lineType = lineType;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getUnitAmount() {
    return unitAmount;
  }

  public void setUnitAmount(BigDecimal unitAmount) {
    this.unitAmount = unitAmount;
  }

  public BigDecimal getLineTotal() {
    return lineTotal;
  }

  public void setLineTotal(BigDecimal lineTotal) {
    this.lineTotal = lineTotal;
  }

  public BigDecimal getTaxRatePercent() {
    return taxRatePercent;
  }

  public void setTaxRatePercent(BigDecimal taxRatePercent) {
    this.taxRatePercent = taxRatePercent;
  }

  public BigDecimal getTaxAmount() {
    return taxAmount;
  }

  public void setTaxAmount(BigDecimal taxAmount) {
    this.taxAmount = taxAmount;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }
}
