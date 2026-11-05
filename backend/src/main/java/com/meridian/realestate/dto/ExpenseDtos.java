package com.meridian.realestate.dto;

import com.meridian.realestate.domain.PropertyExpense;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The property-expense API contracts.
 *
 * <p><b>The total is never taken from the caller.</b> {@code totalAmount} appears on the create and
 * update requests and is deliberately ignored, which reads oddly until you consider what accepting
 * it would mean: {@code ck_property_expenses_total_is_parts} requires the stored row to equal
 * {@code amount + tax_amount}, so a client-supplied total can only ever agree with the parts or be
 * refused by the database as an opaque integrity error. The field is accepted because a screen that
 * reads an expense back and posts the whole record would otherwise fail on an unknown property;
 * it is ignored because the arithmetic is a property of the row, not of the request.</p>
 *
 * <p><b>Operating versus capital is not on this request either.</b> Whether a cost is a cost of the
 * period's letting or an investment in the asset is read from {@code expense_categories.is_capital},
 * which is why the create names a category and nothing else. A caller that could set the split
 * directly could make net operating income say anything.</p>
 *
 * <p>{@code recoverable} and {@code recoveredAmount} are kept apart throughout, because the flag is
 * what the lease entitles the firm to recharge and the amount is what it actually recovered -
 * routinely less, sometimes nothing. Reporting the entitlement as recovery would overstate income.</p>
 */
public final class ExpenseDtos {

  private ExpenseDtos() {}

  /** A row in the expense ledger. */
  public record ExpenseSummary(
      UUID id,
      String expenseCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitCode,
      UUID categoryId,
      String categoryCode,
      String categoryName,
      String categoryGroup,
      /** True when this cost is excluded from net operating income and reported separately. */
      boolean capital,
      UUID maintenanceRequestId,
      UUID vendorId,
      String vendorName,
      LocalDate expenseDate,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal amount,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      String description,
      String invoiceReference,
      boolean recoverable,
      BigDecimal recoveredAmount,
      BigDecimal unrecoveredAmount,
      UUID recordedById,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    /** Must be called inside a transaction: the property, unit, category and vendor are lazy. */
    public static ExpenseSummary from(PropertyExpense expense) {
      return new ExpenseSummary(
          expense.getId(),
          expense.getExpenseCode(),
          expense.getProperty() == null ? null : expense.getProperty().getId(),
          expense.getProperty() == null ? null : expense.getProperty().getPropertyCode(),
          expense.getProperty() == null ? null : expense.getProperty().getName(),
          expense.getUnit() == null ? null : expense.getUnit().getId(),
          expense.getUnit() == null ? null : expense.getUnit().getUnitCode(),
          expense.getCategory() == null ? null : expense.getCategory().getId(),
          expense.getCategory() == null ? null : expense.getCategory().getCode(),
          expense.getCategory() == null ? null : expense.getCategory().getName(),
          expense.getCategory() == null || expense.getCategory().getGroupName() == null
              ? null
              : expense.getCategory().getGroupName().name(),
          expense.isCapital(),
          expense.getMaintenanceRequest() == null ? null : expense.getMaintenanceRequest().getId(),
          expense.getVendor() == null ? null : expense.getVendor().getId(),
          expense.getVendor() == null ? null : expense.getVendor().getName(),
          expense.getExpenseDate(),
          expense.getPeriodStart(),
          expense.getPeriodEnd(),
          expense.getAmount(),
          expense.getTaxAmount(),
          expense.getTotalAmount(),
          expense.getDescription(),
          expense.getInvoiceReference(),
          expense.isRecoverable(),
          expense.getRecoveredAmount(),
          expense.getUnrecoveredAmount(),
          expense.getRecordedBy() == null ? null : expense.getRecordedBy().getId(),
          expense.getCreatedAt(),
          expense.getUpdatedAt());
    }
  }

  /** Everything the expense screen shows for one row, including the fault it came from. */
  public record ExpenseDetail(
      UUID id,
      String expenseCode,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitCode,
      UUID categoryId,
      String categoryCode,
      String categoryName,
      String categoryGroup,
      boolean capital,
      boolean typicallyRecoverable,
      UUID maintenanceRequestId,
      String maintenanceRequestCode,
      UUID vendorId,
      String vendorName,
      LocalDate expenseDate,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal amount,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      String description,
      String invoiceReference,
      boolean recoverable,
      BigDecimal recoveredAmount,
      BigDecimal unrecoveredAmount,
      UUID recordedById,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** The ledger's headline: operating and capital kept apart, exactly as net income needs them. */
  public record ExpenseSummaryResponse(
      LocalDate from,
      LocalDate to,
      /**
       * Costs of the period's letting - the figure net operating income subtracts.
       * Capital expenditure is <b>not</b> in here; it is reported separately below.
       */
      BigDecimal operatingTotal,
      /** Investment in the asset: excluded from net operating income and reported on its own. */
      BigDecimal capitalTotal,
      BigDecimal recoveredTotal,
      BigDecimal unrecoveredTotal,
      /** Operating plus capital - what the portfolio spent - kept out of the two figures above. */
      BigDecimal totalIncurred,
      long operatingCount,
      long capitalCount,
      long recoverableCount,
      List<MonthlyExpenseRow> monthlyTrend,
      LocalDateTime generatedAt) {}

  /** One month of the expense trend, with operating and capital never added together. */
  public record MonthlyExpenseRow(
      int year,
      int month,
      String label,
      BigDecimal operatingTotal,
      BigDecimal capitalTotal,
      BigDecimal recoveredTotal) {}

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /** Record a cost the portfolio bore. */
  public record CreateExpenseRequest(
      @NotNull UUID propertyId,
      UUID unitId,
      /** The heading the cost is recorded under, and hence whether it is capital. */
      @NotNull UUID categoryId,
      UUID maintenanceRequestId,
      UUID vendorId,
      @NotNull LocalDate expenseDate,
      LocalDate periodStart,
      LocalDate periodEnd,
      @NotNull @DecimalMin(value = "0", message = "must not be negative") BigDecimal amount,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal taxAmount,
      @NotBlank @Size(max = 500) String description,
      @Size(max = 120) String invoiceReference,
      /** Null falls back to the category's own {@code typically_recoverable} default. */
      Boolean recoverable,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal recoveredAmount,
      /** Accepted and ignored: the stored total is {@code amount + tax_amount}. */
      BigDecimal totalAmount) {}

  /** Change an expense. A null field is left unchanged; the total is recomputed, never supplied. */
  public record UpdateExpenseRequest(
      UUID unitId,
      UUID categoryId,
      UUID maintenanceRequestId,
      UUID vendorId,
      LocalDate expenseDate,
      LocalDate periodStart,
      LocalDate periodEnd,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal amount,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal taxAmount,
      @Size(max = 500) String description,
      @Size(max = 120) String invoiceReference,
      Boolean recoverable,
      @DecimalMin(value = "0", message = "must not be negative") BigDecimal recoveredAmount,
      /** Accepted and ignored: the stored total is {@code amount + tax_amount}. */
      BigDecimal totalAmount) {}

  /**
   * Record what was actually recharged.
   *
   * <p>Only a recoverable cost can carry a recovery, and never more than it cost - the two rules
   * {@code ck_property_expenses_recovery} states, surfaced here as named conflicts rather than as an
   * integrity error. A recovery of zero is an ordinary correction and is allowed on any expense;
   * anything else requires the lease to have entitled it.</p>
   */
  public record RecordRecoveryRequest(
      @NotNull @DecimalMin(value = "0", message = "must not be negative") BigDecimal recoveredAmount) {}
}
