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
import java.time.LocalDate;
import java.util.UUID;

/**
 * One cost the portfolio incurred, recorded against the property that bore it.
 *
 * <p>An expense is recorded against a property, optionally against a unit inside it, optionally
 * against the maintenance request that caused it and optionally against the vendor who did the work.
 * Those optional links are what make the ledger answerable: a property's maintenance spend can be
 * traced to the faults that produced it, and a vendor's cost can be totalled without reading every
 * description.</p>
 *
 * <p><b>The schema's arithmetic is enforced on the row, and the service must not fight it.</b></p>
 * <ul>
 *   <li>{@code ck_property_expenses_total_is_parts}: {@code total_amount = amount + tax_amount}. As on
 *       an invoice, the arithmetic has to hold; the caller sets {@code totalAmount} to the sum rather
 *       than to whatever the paperwork said.</li>
 *   <li>{@code ck_property_expenses_amount_non_negative}: {@code amount >= 0}.</li>
 *   <li>{@code ck_property_expenses_tax_non_negative}: {@code tax_amount >= 0}.</li>
 *   <li>{@code ck_property_expenses_total_non_negative}: {@code total_amount >= 0}.</li>
 *   <li>{@code ck_property_expenses_recovered_non_negative}: {@code recovered_amount >= 0}.</li>
 *   <li>{@code ck_property_expenses_recovery}: {@code recovered_amount <= total_amount AND
 *       (recovered_amount = 0 OR is_recoverable = true)} - a cost can never be recovered beyond what
 *       it cost, and nothing can be recovered that is not marked recoverable. That combination is a
 *       data error that would show as phantom credit in a recovery report.</li>
 *   <li>{@code ck_property_expenses_period}: {@code period_end IS NULL OR period_start IS NULL OR
 *       period_end >= period_start} - a service period cannot run backwards.</li>
 * </ul>
 *
 * <p><b>The operating-versus-capital split, and NOI.</b> The distinction does not live on this row:
 * it is read from {@link ExpenseCategory#isCapital()}. Operating spend is a cost of the period's
 * letting; capital spend improves the asset and is reported separately. The service must perform that
 * split rather than summing the ledger, because summing it would add two genuinely different kinds of
 * money together. The V15 header states the rule the product is built on: <em>net operating income is
 * collected rent minus operating expenses, excluding capital expenses and deposits - capital
 * expenditure is excluded deliberately, reported separately rather than netted off, and a deposit held
 * is a liability, not a cost or a revenue.</em> Netting capital off NOI would overstate cost and
 * understate the portfolio's running result, and the error would be invisible because both figures are
 * real.</p>
 *
 * <p>{@code isRecoverable} and {@code recoveredAmount} are kept apart on purpose. The flag records
 * what the lease entitles the firm to recharge; the amount records what was actually recovered, which
 * is routinely less and sometimes nothing. Reporting the entitlement as recovery would overstate
 * income.</p>
 */
@Entity
@Table(name = "property_expenses")
public class PropertyExpense extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "expense_code", nullable = false, length = 30, unique = true)
  private String expenseCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /** Set when the cost is attributable to one unit rather than to the property at large. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "unit_id")
  private Unit unit;

  /** The heading the cost is recorded under - and hence whether it is operating or capital. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "category_id", nullable = false)
  private ExpenseCategory category;

  /** The fault that caused the cost, when there was one. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "maintenance_request_id")
  private MaintenanceRequest maintenanceRequest;

  /** Who did the work, when a vendor did. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "vendor_id")
  private Vendor vendor;

  @Column(name = "expense_date", nullable = false)
  private LocalDate expenseDate;

  @Column(name = "period_start")
  private LocalDate periodStart;

  @Column(name = "period_end")
  private LocalDate periodEnd;

  /** The net amount, before tax. */
  @Column(name = "amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal taxAmount = BigDecimal.ZERO;

  /** The gross amount. The schema requires this to equal {@code amount + taxAmount}. */
  @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal totalAmount;

  @Column(name = "description", nullable = false, length = 500)
  private String description;

  @Column(name = "invoice_reference", length = 120)
  private String invoiceReference;

  /** True where the lease allows this cost to be recharged to the tenant. */
  @Column(name = "is_recoverable", nullable = false)
  private boolean recoverable = false;

  /** What was actually recovered - never more than {@code totalAmount}. */
  @Column(name = "recovered_amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal recoveredAmount = BigDecimal.ZERO;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recorded_by")
  private User recordedBy;

  /** True when this cost is excluded from net operating income and reported separately. */
  public boolean isCapital() {
    return category != null && category.isCapital();
  }

  /** True when this cost is part of the NOI calculation. */
  public boolean isOperating() {
    return !isCapital();
  }

  /** What remains unrecovered on a rechargeable cost. */
  public BigDecimal getUnrecoveredAmount() {
    if (totalAmount == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal recovered = recoveredAmount == null ? BigDecimal.ZERO : recoveredAmount;
    return totalAmount.subtract(recovered);
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getExpenseCode() {
    return expenseCode;
  }

  public void setExpenseCode(String expenseCode) {
    this.expenseCode = expenseCode;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Unit getUnit() {
    return unit;
  }

  public void setUnit(Unit unit) {
    this.unit = unit;
  }

  public ExpenseCategory getCategory() {
    return category;
  }

  public void setCategory(ExpenseCategory category) {
    this.category = category;
  }

  public MaintenanceRequest getMaintenanceRequest() {
    return maintenanceRequest;
  }

  public void setMaintenanceRequest(MaintenanceRequest maintenanceRequest) {
    this.maintenanceRequest = maintenanceRequest;
  }

  public Vendor getVendor() {
    return vendor;
  }

  public void setVendor(Vendor vendor) {
    this.vendor = vendor;
  }

  public LocalDate getExpenseDate() {
    return expenseDate;
  }

  public void setExpenseDate(LocalDate expenseDate) {
    this.expenseDate = expenseDate;
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

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
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

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getInvoiceReference() {
    return invoiceReference;
  }

  public void setInvoiceReference(String invoiceReference) {
    this.invoiceReference = invoiceReference;
  }

  public boolean isRecoverable() {
    return recoverable;
  }

  public void setRecoverable(boolean recoverable) {
    this.recoverable = recoverable;
  }

  public BigDecimal getRecoveredAmount() {
    return recoveredAmount;
  }

  public void setRecoveredAmount(BigDecimal recoveredAmount) {
    this.recoveredAmount = recoveredAmount;
  }

  public User getRecordedBy() {
    return recordedBy;
  }

  public void setRecordedBy(User recordedBy) {
    this.recordedBy = recordedBy;
  }
}
