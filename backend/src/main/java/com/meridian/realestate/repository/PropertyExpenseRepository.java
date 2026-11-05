package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PropertyExpense;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The expense ledger.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} the caller has already validated.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. An expense is archived rather than removed,
 * because a property's running result and the vendor totals built from it must stay reconcilable.</p>
 *
 * <p><b>The operating and capital reads are separate on purpose.</b> {@link #sumOperatingExpenses}
 * is the net-operating-income input; {@link #sumCapitalExpenses} is the investment figure that NOI
 * excludes and that is reported on its own. They are not two views of one number and must not be
 * added together: capital expenditure improves the asset rather than costing the period, and netting
 * it off would overstate cost and understate the portfolio's running result. See
 * {@link com.meridian.realestate.domain.PropertyExpense}. The split is read from the category
 * ({@code expense_categories.is_capital}), never from an arbitrary column on the expense row.</p>
 *
 * <p>The sums are taken over {@code total_amount} - the tax-inclusive figure the ledger carries - and
 * over {@code expense_date}, the date the cost was incurred, rather than over a service period.
 * A period that straddles a month boundary is reported in the month it was incurred, which is how the
 * trend chart and the period statements agree with each other.</p>
 */
public interface PropertyExpenseRepository extends JpaRepository<PropertyExpense, UUID> {

  Optional<PropertyExpense> findByIdAndDeletedAtIsNull(UUID id);

  Optional<PropertyExpense> findByExpenseCodeIgnoreCaseAndDeletedAtIsNull(String expenseCode);

  boolean existsByExpenseCodeIgnoreCaseAndDeletedAtIsNull(String expenseCode);

  Page<PropertyExpense> findAllByDeletedAtIsNull(Pageable pageable);

  List<PropertyExpense> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** Everything the property bore - the expense tab of the asset. */
  List<PropertyExpense> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  List<PropertyExpense> findAllByUnitIdAndDeletedAtIsNull(UUID unitId);

  List<PropertyExpense> findAllByCategoryIdAndDeletedAtIsNull(UUID categoryId);

  /** The cost of one fault, once it has been priced - what makes maintenance spend traceable. */
  List<PropertyExpense> findAllByMaintenanceRequestIdAndDeletedAtIsNull(UUID maintenanceRequestId);

  List<PropertyExpense> findAllByVendorIdAndDeletedAtIsNull(UUID vendorId);

  /**
   * Operating spend over a period - <b>the net operating income input</b>.
   *
   * <p>Costs recorded under a non-capital category, summed across the whole portfolio. NOI is
   * collected rent minus this figure, with capital expenditure excluded and deposits excluded
   * entirely; nothing else belongs in the subtraction.</p>
   */
  @Query(
      "select coalesce(sum(e.totalAmount), 0) from PropertyExpense e "
          + "where e.deletedAt is null and e.category.capital = false "
          + "and e.expenseDate between :from and :to")
  BigDecimal sumOperatingExpenses(@Param("from") LocalDate from, @Param("to") LocalDate to);

  /** The same operating figure for one property, for the property-level profitability report. */
  @Query(
      "select coalesce(sum(e.totalAmount), 0) from PropertyExpense e "
          + "where e.deletedAt is null and e.property.id = :propertyId "
          + "and e.category.capital = false "
          + "and e.expenseDate between :from and :to")
  BigDecimal sumOperatingExpensesForProperty(
      @Param("propertyId") UUID propertyId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * Capital expenditure over a period.
   *
   * <p>Deliberately outside NOI and reported on its own: a replacement lift improves the asset rather
   * than costing the period's letting, so it is excluded from net operating income rather than netted
   * off inside it.</p>
   */
  @Query(
      "select coalesce(sum(e.totalAmount), 0) from PropertyExpense e "
          + "where e.deletedAt is null and e.category.capital = true "
          + "and e.expenseDate between :from and :to")
  BigDecimal sumCapitalExpenses(@Param("from") LocalDate from, @Param("to") LocalDate to);

  /** The same capital figure for one property. */
  @Query(
      "select coalesce(sum(e.totalAmount), 0) from PropertyExpense e "
          + "where e.deletedAt is null and e.property.id = :propertyId "
          + "and e.category.capital = true "
          + "and e.expenseDate between :from and :to")
  BigDecimal sumCapitalExpensesForProperty(
      @Param("propertyId") UUID propertyId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * What was incurred and what was recovered, side by side.
   *
   * <p>The two are kept apart because they answer different questions: incurred is what the portfolio
   * spent, recovered is what the leases allowed the firm to claw back, and the gap is the unrecovered
   * share management actually cares about. Reporting the entitlement as recovery would overstate
   * income, which is why {@code recovered_amount} exists as its own column.</p>
   */
  @Query(
      "select coalesce(sum(e.totalAmount), 0) as incurred, "
          + "coalesce(sum(e.recoveredAmount), 0) as recovered "
          + "from PropertyExpense e where e.deletedAt is null")
  RecoveryTotals recoveryTotals();

  /** The same recovery picture for one property. */
  @Query(
      "select coalesce(sum(e.totalAmount), 0) as incurred, "
          + "coalesce(sum(e.recoveredAmount), 0) as recovered "
          + "from PropertyExpense e where e.deletedAt is null and e.property.id = :propertyId")
  RecoveryTotals recoveryTotalsForProperty(@Param("propertyId") UUID propertyId);

  /**
   * The monthly expense trend: incurred and recovered per calendar month over a period.
   *
   * <p>Grouped in the database rather than by loading the ledger and bucketing in memory, because the
   * income-versus-expense chart asks this question over every expense the portfolio has ever recorded.
   * Ordered by the month itself, which is the one ordering the chart depends on and which therefore
   * cannot be left to a caller.</p>
   */
  @Query(
      "select year(e.expenseDate) as periodYear, "
          + "month(e.expenseDate) as periodMonth, "
          + "coalesce(sum(e.totalAmount), 0) as incurred, "
          + "coalesce(sum(e.recoveredAmount), 0) as recovered "
          + "from PropertyExpense e "
          + "where e.deletedAt is null and e.expenseDate between :from and :to "
          + "group by year(e.expenseDate), month(e.expenseDate) "
          + "order by year(e.expenseDate), month(e.expenseDate)")
  List<MonthlyExpenseTrend> monthlyExpenseTrend(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /**
   * The ledger, with the associations the list reads eager-fetched so it does not incur a query per
   * row: the property, the unit and the category - and the category is the one that carries the
   * operating-versus-capital flag the list shows.
   */
  @Query(
      "select e from PropertyExpense e "
          + "left join fetch e.property "
          + "left join fetch e.unit "
          + "left join fetch e.category "
          + "where e.deletedAt is null")
  List<PropertyExpense> findAllForLedger();

  /** What was incurred and what was recovered over the set the query looked at. */
  interface RecoveryTotals {

    BigDecimal getIncurred();

    BigDecimal getRecovered();
  }

  /** One month of the expense trend. */
  interface MonthlyExpenseTrend {

    int getPeriodYear();

    int getPeriodMonth();

    BigDecimal getIncurred();

    BigDecimal getRecovered();
  }
}
