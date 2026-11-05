package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.ExpenseCategory;
import com.meridian.realestate.domain.MaintenanceRequest;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyExpense;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Vendor;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.ExpenseDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.PropertyExpenseRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.VendorRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expense ledger: what the portfolio costs to hold, and which half of net income it falls in.
 *
 * <h2>Why this class exists</h2>
 * Every expense is an amount of money with a tax part, a heading that decides whether it is a cost of
 * the period or an investment in the asset, and - often - a rechargeable part that was only partly
 * recovered. Those are one subject: a cost cannot be recorded without deciding what kind it is, and
 * the two figures the lease entitles the firm to and what it actually clawed back are routinely
 * different. Stating the arithmetic, the recovery rules and the operating-versus-capital split in one
 * place is what keeps a property's running result reconcilable with the rows it is drawn from.
 *
 * <h2>The rules it protects</h2>
 * <ul>
 *   <li><b>The total is computed, never accepted.</b> {@code total_amount} is always {@code amount +
 *       tax_amount}, at the product's money scale. {@code ck_property_expenses_total_is_parts}
 *       requires it on the row, and a request that names a total is ignored rather than trusted: a
 *       stored row whose printed parts do not sum is an invoice nobody can reconcile.</li>
 *   <li><b>Recovery is bounded by the cost and gated by the lease.</b> A recovery can never exceed
 *       what the cost came to ({@code EXPENSE_RECOVERY_EXCEEDS_TOTAL}, 409), and nothing can be
 *       recovered from a cost the lease does not allow to be recharged
 *       ({@code EXPENSE_RECOVERY_NOT_ALLOWED}, 409). Both are checked before anything is written, so
 *       the caller is told which rule it broke rather than being handed the database's own integrity
 *       text. A recovery of zero is an ordinary correction and is allowed on any cost.</li>
 *   <li><b>Operating is never mixed with capital.</b> The split is read from the category
 *       ({@code expense_categories.is_capital}), never from anything on the expense row, and the
 *       summary reports the two separately and never summed into one "expenses" figure. The V15
 *       header states the rule the product is built on: <em>net operating income is collected rent
 *       minus operating expenses, excluding capital expenses and deposits</em>. Capital expenditure
 *       is excluded deliberately and reported on its own; adding it in would overstate cost and
 *       understate the portfolio's running result, and the error would be invisible because both
 *       figures are real money that was really spent. Deposits need no separate exclusion here: a
 *       deposit held is a liability rather than a cost and is never a {@code property_expenses} row,
 *       so the ledger cannot carry one to subtract in the first place.</li>
 *   <li><b>Archiving is soft, and once.</b> A cost is archived rather than removed, because the
 *       period statements, the vendor totals and the recovery report built from it must stay
 *       reconcilable.</li>
 *   <li><b>Recoverable is not recovered.</b> {@code recoverable} says what the lease entitles, and
 *       {@code recoveredAmount} says what was actually clawed back. The two are never conflated in
 *       the maths, because reporting the entitlement as income would overstate it.</li>
 * </ul>
 *
 * <h2>The cost trail</h2>
 * An expense may name the maintenance request that caused it. That link is what makes a property's
 * maintenance spend traceable to the faults that produced it rather than appearing as an unexplained
 * lump - it is the reason {@code V14} carries an estimate and an actual on the request and {@code V15}
 * carries the request reference on the cost.
 *
 * <h2>A note on the summary, and on a gap reported rather than patched</h2>
 * {@code PropertyExpenseRepository} offers SQL sums for the operating and capital figures
 * ({@code sumOperatingExpenses}, {@code sumCapitalExpenses}) and a grouped monthly trend. The summary
 * here performs the split over the ledger read instead, for one reason: the figure has to be the sum
 * of the rows a reader is looking at, and doing it in one place makes the rule - capital never enters
 * the operating figure - a statement in the code rather than a property of a query a reader has to go
 * and find. The repository's own sums remain the right tool for the single-property profitability
 * report. Separately, the migration chain seeds no business-number series for expenses
 * ({@code business_number_sequences} has rows for {@code PROPERTY} through {@code SALE} and none for
 * this table), so an expense code is minted from the {@code MAINTENANCE} series - the module
 * {@code V15} files the ledger under. That is a gap in the seed rather than a decision, and it is
 * reported rather than worked around.
 *
 * <h2>A note on paging</h2>
 * There is no combined paged query for this register's six filters. Rather than put a literal
 * {@code order by} in a query, which this product forbids because it silently overrides the order the
 * caller asked for, the ledger is filtered, sorted and sliced here so the page the caller asked for
 * is the page they get, with correct totals.
 */
@Service
public class ExpenseService {

  private static final Map<String, Comparator<PropertyExpense>> SORTS =
      Map.ofEntries(
          Map.entry("createdAt", Comparator.comparing(PropertyExpense::getCreatedAt, nullSafe())),
          Map.entry("updatedAt", Comparator.comparing(PropertyExpense::getUpdatedAt, nullSafe())),
          Map.entry("expenseCode", Comparator.comparing(PropertyExpense::getExpenseCode, nullSafe())),
          Map.entry("expenseDate", Comparator.comparing(PropertyExpense::getExpenseDate, nullSafe())),
          Map.entry("totalAmount", Comparator.comparing(PropertyExpense::getTotalAmount, nullSafe())),
          Map.entry("amount", Comparator.comparing(PropertyExpense::getAmount, nullSafe())),
          Map.entry(
              "category.name",
              Comparator.comparing(
                  (PropertyExpense e) -> e.getCategory() == null ? null : e.getCategory().getName(),
                  nullSafe())),
          Map.entry(
              "property.name",
              Comparator.comparing(
                  (PropertyExpense e) -> e.getProperty() == null ? null : e.getProperty().getName(),
                  nullSafe())),
          Map.entry(
              "vendor.name",
              Comparator.comparing(
                  (PropertyExpense e) -> e.getVendor() == null ? null : e.getVendor().getName(),
                  nullSafe())));

  private final PropertyExpenseRepository expenses;
  private final ExpenseCategoryService expenseCategories;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final MaintenanceRequestRepository maintenanceRequests;
  private final VendorRepository vendors;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;
  private final AppProperties appProperties;

  public ExpenseService(
      PropertyExpenseRepository expenses,
      ExpenseCategoryService expenseCategories,
      PropertyRepository properties,
      UnitRepository units,
      MaintenanceRequestRepository maintenanceRequests,
      VendorRepository vendors,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope,
      AppProperties appProperties) {
    this.expenses = expenses;
    this.expenseCategories = expenseCategories;
    this.properties = properties;
    this.units = units;
    this.maintenanceRequests = maintenanceRequests;
    this.vendors = vendors;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
    this.appProperties = appProperties;
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** The live expense behind an id, or a 404. Archived expenses are not addressable. */
  @Transactional(readOnly = true)
  public PropertyExpense require(UUID expenseId) {
    return expenses
        .findByIdAndDeletedAtIsNull(expenseId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("EXPENSE_NOT_FOUND")));
  }

  /** The ledger, filtered, searched, sorted and paged. */
  @Transactional(readOnly = true)
  public PageResponse<ExpenseDtos.ExpenseSummary> list(
      UUID propertyId,
      UUID unitId,
      UUID categoryId,
      Boolean capital,
      UUID vendorId,
      UUID maintenanceRequestId,
      LocalDate from,
      LocalDate to,
      String search,
      Pageable pageable) {

    String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);

    List<ExpenseDtos.ExpenseSummary> rows =
        expenses.findAllForLedger().stream()
            .filter(expense -> propertyId == null || matchesProperty(expense, propertyId))
            .filter(expense -> unitId == null || matchesUnit(expense, unitId))
            .filter(
                expense ->
                    categoryId == null
                        || (expense.getCategory() != null
                            && categoryId.equals(expense.getCategory().getId())))
            // The operating-versus-capital filter reads the category's flag, exactly as every other
            // use of the split does - never a column on the expense row.
            .filter(expense -> capital == null || capital == expense.isCapital())
            .filter(expense -> vendorId == null || matchesVendor(expense, vendorId))
            .filter(
                expense ->
                    maintenanceRequestId == null
                        || (expense.getMaintenanceRequest() != null
                            && maintenanceRequestId.equals(expense.getMaintenanceRequest().getId())))
            .filter(expense -> withinDate(expense, from, to))
            .filter(expense -> term == null || matchesSearch(expense, term))
            .sorted(comparator(pageable))
            .map(ExpenseDtos.ExpenseSummary::from)
            .toList();

    return paginate(rows, pageable);
  }

  /** One expense's full record, including the fault it came from. */
  @Transactional(readOnly = true)
  public ExpenseDtos.ExpenseDetail detail(UUID expenseId) {
    return detailOf(require(expenseId));
  }

  /**
   * What the portfolio spent over a period, with operating and capital kept apart.
   *
   * <p>Read the two figures as an instruction rather than a report: {@code operatingTotal} is the
   * figure net operating income subtracts, {@code capitalTotal} is the investment figure that is
   * excluded from it, and they are never added into one number here - {@code totalIncurred} exists
   * only so a reader can see what the portfolio actually paid without that sum being mistaken for
   * the NOI input.</p>
   */
  @Transactional(readOnly = true)
  public ExpenseDtos.ExpenseSummaryResponse summary(LocalDate from, LocalDate to) {
    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate start =
        from == null
            ? end.withDayOfMonth(1).minusMonths(Math.max(appProperties.getPortfolio().getTrendMonths(), 1) - 1L)
            : from;
    if (start.isAfter(end)) {
      throw ApiException.badRequest("The start of the period cannot fall after its end");
    }

    BigDecimal operating = BigDecimal.ZERO;
    BigDecimal capital = BigDecimal.ZERO;
    BigDecimal recovered = BigDecimal.ZERO;
    long operatingCount = 0;
    long capitalCount = 0;
    long recoverableCount = 0;

    Map<YearMonth, BigDecimal> operatingByMonth = emptyMonths(start, end);
    Map<YearMonth, BigDecimal> capitalByMonth = emptyMonths(start, end);
    Map<YearMonth, BigDecimal> recoveredByMonth = emptyMonths(start, end);

    for (PropertyExpense expense : expenses.findAllForLedger()) {
      if (!withinDate(expense, start, end)) continue;
      YearMonth month = YearMonth.from(expense.getExpenseDate());
      BigDecimal total = MoneyPolicy.money(expense.getTotalAmount());
      BigDecimal clawedBack = MoneyPolicy.money(expense.getRecoveredAmount());

      // The one place the split is made. Capital is a different kind of money and is deliberately not
      // added to the operating figure - see the class note and the V15 header.
      if (expense.isCapital()) {
        capital = capital.add(total);
        capitalCount++;
        capitalByMonth.merge(month, total, BigDecimal::add);
      } else {
        operating = operating.add(total);
        operatingCount++;
        operatingByMonth.merge(month, total, BigDecimal::add);
      }
      if (expense.isRecoverable()) recoverableCount++;
      recovered = recovered.add(clawedBack);
      recoveredByMonth.merge(month, clawedBack, BigDecimal::add);
    }

    BigDecimal totalIncurred = MoneyPolicy.money(operating.add(capital));
    BigDecimal recoveredTotal = MoneyPolicy.money(recovered);

    List<ExpenseDtos.MonthlyExpenseRow> trend = new ArrayList<>();
    for (YearMonth month : operatingByMonth.keySet()) {
      trend.add(
          new ExpenseDtos.MonthlyExpenseRow(
              month.getYear(),
              month.getMonthValue(),
              month.getMonth().name().substring(0, 3) + " " + month.getYear(),
              MoneyPolicy.money(operatingByMonth.get(month)),
              MoneyPolicy.money(capitalByMonth.getOrDefault(month, BigDecimal.ZERO)),
              MoneyPolicy.money(recoveredByMonth.getOrDefault(month, BigDecimal.ZERO))));
    }

    return new ExpenseDtos.ExpenseSummaryResponse(
        start,
        end,
        MoneyPolicy.money(operating),
        MoneyPolicy.money(capital),
        recoveredTotal,
        MoneyPolicy.money(totalIncurred.subtract(recoveredTotal)),
        totalIncurred,
        operatingCount,
        capitalCount,
        recoverableCount,
        trend,
        java.time.LocalDateTime.now());
  }

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /** Record a cost the portfolio bore. The total is computed here and never taken from the caller. */
  @Transactional
  public ExpenseDtos.ExpenseDetail create(ExpenseDtos.CreateExpenseRequest request) {
    User actor = accessScope.requireUser();

    Property property = requireProperty(request.propertyId());
    Unit unit = request.unitId() == null ? null : requireUnit(request.unitId());
    assertUnitBelongs(property, unit);
    ExpenseCategory category = expenseCategories.require(request.categoryId());
    MaintenanceRequest maintenanceRequest =
        request.maintenanceRequestId() == null
            ? null
            : requireMaintenanceRequest(request.maintenanceRequestId());
    Vendor vendor = request.vendorId() == null ? null : requireVendor(request.vendorId());

    // The arithmetic, at the product's money scale. request.totalAmount() is deliberately never read:
    // the stored total is always the sum of the two parts, as ck_property_expenses_total_is_parts
    // requires.
    BigDecimal amount = MoneyPolicy.money(request.amount());
    BigDecimal tax = MoneyPolicy.money(request.taxAmount());
    BigDecimal total = MoneyPolicy.money(amount.add(tax));

    // Null falls back to the category's own default: a heading that is normally rechargeable offers
    // that as a starting point, but what the lease on this property allows is decided per cost.
    boolean recoverable =
        request.recoverable() == null ? category.isTypicallyRecoverable() : request.recoverable();
    BigDecimal recovered = MoneyPolicy.money(request.recoveredAmount());
    assertRecovery(recovered, total, recoverable);
    assertPeriod(request.periodStart(), request.periodEnd());

    PropertyExpense expense = new PropertyExpense();
    expense.setExpenseCode(numbers.next(BusinessNumberService.MAINTENANCE));
    expense.setProperty(property);
    expense.setUnit(unit);
    expense.setCategory(category);
    expense.setMaintenanceRequest(maintenanceRequest);
    expense.setVendor(vendor);
    expense.setExpenseDate(request.expenseDate());
    expense.setPeriodStart(request.periodStart());
    expense.setPeriodEnd(request.periodEnd());
    expense.setAmount(amount);
    expense.setTaxAmount(tax);
    expense.setTotalAmount(total);
    expense.setDescription(request.description().trim());
    expense.setInvoiceReference(trim(request.invoiceReference()));
    expense.setRecoverable(recoverable);
    expense.setRecoveredAmount(recovered);
    expense.setRecordedBy(actor);
    expense.setCreatedBy(actor.getId());
    expense.setUpdatedBy(actor.getId());

    PropertyExpense saved = expenses.save(expense);

    recordAudit(
        actor,
        AuditActions.CREATE,
        "Recorded expense " + saved.getExpenseCode() + " of " + saved.getTotalAmount()
            + " under " + category.getCode() + " at " + property.getPropertyCode()
            + (saved.isCapital() ? " (capital)" : ""),
        saved,
        null,
        saved.getTotalAmount() == null ? null : saved.getTotalAmount().toPlainString());

    return detailOf(saved);
  }

  /**
   * Change an expense.
   *
   * <p>Whenever the amount or the tax moves, the total is recomputed from the two of them - this
   * method never writes a total of its own and never accepts one. Changing the category reclassifies
   * the cost between operating and capital automatically, because the split is read from the heading
   * and nowhere else; that is deliberate, and it is the reason the category is a record rather than a
   * field a caller may set freely.</p>
   */
  @Transactional
  public ExpenseDtos.ExpenseDetail update(
      UUID expenseId, ExpenseDtos.UpdateExpenseRequest request) {
    User actor = accessScope.requireUser();
    PropertyExpense expense = require(expenseId);

    if (request.unitId() != null) {
      Unit unit = requireUnit(request.unitId());
      assertUnitBelongs(expense.getProperty(), unit);
      expense.setUnit(unit);
    }
    if (request.categoryId() != null) {
      expense.setCategory(expenseCategories.require(request.categoryId()));
    }
    if (request.maintenanceRequestId() != null) {
      expense.setMaintenanceRequest(requireMaintenanceRequest(request.maintenanceRequestId()));
    }
    if (request.vendorId() != null) expense.setVendor(requireVendor(request.vendorId()));
    if (request.expenseDate() != null) expense.setExpenseDate(request.expenseDate());

    LocalDate periodStart =
        request.periodStart() == null ? expense.getPeriodStart() : request.periodStart();
    LocalDate periodEnd = request.periodEnd() == null ? expense.getPeriodEnd() : request.periodEnd();
    assertPeriod(periodStart, periodEnd);
    expense.setPeriodStart(periodStart);
    expense.setPeriodEnd(periodEnd);

    BigDecimal amount =
        request.amount() == null ? MoneyPolicy.money(expense.getAmount()) : MoneyPolicy.money(request.amount());
    BigDecimal tax =
        request.taxAmount() == null
            ? MoneyPolicy.money(expense.getTaxAmount())
            : MoneyPolicy.money(request.taxAmount());
    BigDecimal total = MoneyPolicy.money(amount.add(tax));

    boolean recoverable =
        request.recoverable() == null ? expense.isRecoverable() : request.recoverable();
    BigDecimal recovered =
        request.recoveredAmount() == null
            ? MoneyPolicy.money(expense.getRecoveredAmount())
            : MoneyPolicy.money(request.recoveredAmount());
    assertRecovery(recovered, total, recoverable);

    expense.setAmount(amount);
    expense.setTaxAmount(tax);
    expense.setTotalAmount(total);
    expense.setRecoverable(recoverable);
    expense.setRecoveredAmount(recovered);
    if (request.description() != null && !request.description().isBlank()) {
      expense.setDescription(request.description().trim());
    }
    if (request.invoiceReference() != null) {
      expense.setInvoiceReference(trim(request.invoiceReference()));
    }
    expense.setUpdatedBy(actor.getId());

    PropertyExpense saved = expenses.save(expense);
    recordAudit(
        actor,
        AuditActions.UPDATE,
        "Changed expense " + saved.getExpenseCode() + " (" + saved.getTotalAmount() + ")",
        saved);
    return detailOf(saved);
  }

  /**
   * Record what was actually recharged.
   *
   * <p>Both rules are checked before the write: nothing may be recovered beyond what the cost came to,
   * and nothing positive may be recovered from a cost the lease does not allow to be recharged. The
   * second is why a recovery is not simply a number that can be filled in - it is a claim against a
   * tenant, and a claim the lease does not support is a data error that would show as phantom credit
   * in a recovery report.</p>
   */
  @Transactional
  public ExpenseDtos.ExpenseDetail recordRecovery(
      UUID expenseId, ExpenseDtos.RecordRecoveryRequest request) {
    User actor = accessScope.requireUser();
    PropertyExpense expense = require(expenseId);

    BigDecimal recovered = MoneyPolicy.money(request.recoveredAmount());
    assertRecovery(recovered, MoneyPolicy.money(expense.getTotalAmount()), expense.isRecoverable());

    expense.setRecoveredAmount(recovered);
    expense.setUpdatedBy(actor.getId());
    PropertyExpense saved = expenses.save(expense);

    recordAudit(
        actor,
        AuditActions.UPDATE,
        "Recorded a recovery of " + recovered + " against expense " + saved.getExpenseCode(),
        saved);
    return detailOf(saved);
  }

  /** Archive an expense - soft delete, once. */
  @Transactional
  public ExpenseDtos.ExpenseDetail archive(UUID expenseId) {
    User actor = accessScope.requireUser();

    // Loaded including archived rows so "already archived" is distinguished from "never existed".
    PropertyExpense expense =
        expenses
            .findById(expenseId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("EXPENSE_NOT_FOUND")));

    if (expense.isDeleted()) {
      throw ApiException.rule("EXPENSE_ALREADY_ARCHIVED", "This expense has already been archived");
    }

    expense.setDeletedAt(java.time.LocalDateTime.now());
    expense.setUpdatedBy(actor.getId());
    PropertyExpense saved = expenses.save(expense);

    recordAudit(actor, AuditActions.DELETE, "Archived expense " + saved.getExpenseCode(), saved);
    return detailOf(saved);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * The two recovery rules the schema states, as named conflicts.
   *
   * <p>Order matters for the message a caller reads: an amount that is impossible is reported as
   * impossible before the entitlement is discussed, because "more than it cost" is the error whatever
   * the lease says.</p>
   */
  private static void assertRecovery(BigDecimal recovered, BigDecimal total, boolean recoverable) {
    if (recovered.compareTo(total) > 0) {
      throw ApiException.rule(
          "EXPENSE_RECOVERY_EXCEEDS_TOTAL",
          ErrorMessages.friendly("EXPENSE_RECOVERY_EXCEEDS_TOTAL"));
    }
    // Zero is an ordinary correction to any cost; anything else is a claim the lease has to support.
    if (MoneyPolicy.isPositive(recovered) && !recoverable) {
      throw ApiException.rule(
          "EXPENSE_RECOVERY_NOT_ALLOWED", ErrorMessages.friendly("EXPENSE_RECOVERY_NOT_ALLOWED"));
    }
  }

  /** A service period cannot run backwards; the schema says so and a caller deserves to be told. */
  private static void assertPeriod(LocalDate start, LocalDate end) {
    if (start != null && end != null && end.isBefore(start)) {
      throw ApiException.badRequest("The service period cannot end before it starts");
    }
  }

  private static void assertUnitBelongs(Property property, Unit unit) {
    if (unit == null) return;
    if (property == null) {
      throw ApiException.badRequest("A unit cannot be set without its property");
    }
    if (unit.getProperty() == null || !property.getId().equals(unit.getProperty().getId())) {
      throw ApiException.badRequest("The unit does not belong to the chosen property");
    }
  }

  private ExpenseDtos.ExpenseDetail detailOf(PropertyExpense expense) {
    ExpenseCategory category = expense.getCategory();
    return new ExpenseDtos.ExpenseDetail(
        expense.getId(),
        expense.getExpenseCode(),
        expense.getProperty() == null ? null : expense.getProperty().getId(),
        expense.getProperty() == null ? null : expense.getProperty().getPropertyCode(),
        expense.getProperty() == null ? null : expense.getProperty().getName(),
        expense.getUnit() == null ? null : expense.getUnit().getId(),
        expense.getUnit() == null ? null : expense.getUnit().getUnitCode(),
        category == null ? null : category.getId(),
        category == null ? null : category.getCode(),
        category == null ? null : category.getName(),
        category == null || category.getGroupName() == null ? null : category.getGroupName().name(),
        expense.isCapital(),
        category != null && category.isTypicallyRecoverable(),
        expense.getMaintenanceRequest() == null ? null : expense.getMaintenanceRequest().getId(),
        expense.getMaintenanceRequest() == null
            ? null
            : expense.getMaintenanceRequest().getRequestCode(),
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

  private void recordAudit(
      User actor,
      String action,
      String description,
      PropertyExpense expense,
      String previousValue,
      String newValue) {
    // The property, unit and fault references are written through the audit entry's own columns: that
    // is what the property screen's activity tab and the maintenance request's cost trail read by.
    audit.recordEntityChange(
        actor.getId(),
        actor.getFullName(),
        AuditActions.EXPENSE,
        action,
        description,
        "PROPERTY_EXPENSE",
        expense.getId(),
        expense.getExpenseCode(),
        previousValue,
        newValue,
        referencesFor(expense));
  }

  private void recordAudit(User actor, String action, String description, PropertyExpense expense) {
    recordAudit(actor, action, description, expense, null, null);
  }

  /**
   * The audit entry's named references for a cost.
   *
   * <p>One helper, because the columns are positional and most of them are null: a single mis-ordered
   * call would file an entry against another asset while still looking correct.</p>
   */
  private static AuditRecordingService.EntityReferences referencesFor(PropertyExpense expense) {
    return new AuditRecordingService.EntityReferences(
        expense.getProperty() == null ? null : expense.getProperty().getId(),
        expense.getUnit() == null ? null : expense.getUnit().getId(),
        null,
        null,
        null,
        null,
        null,
        null,
        expense.getMaintenanceRequest() == null ? null : expense.getMaintenanceRequest().getId());
  }

  private Property requireProperty(UUID id) {
    return properties
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  private Unit requireUnit(UUID id) {
    return units
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("UNIT_NOT_FOUND")));
  }

  private MaintenanceRequest requireMaintenanceRequest(UUID id) {
    return maintenanceRequests
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("MAINTENANCE_NOT_FOUND")));
  }

  private Vendor requireVendor(UUID id) {
    return vendors
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("VENDOR_NOT_FOUND")));
  }

  private static boolean matchesProperty(PropertyExpense expense, UUID propertyId) {
    return expense.getProperty() != null && propertyId.equals(expense.getProperty().getId());
  }

  private static boolean matchesUnit(PropertyExpense expense, UUID unitId) {
    return expense.getUnit() != null && unitId.equals(expense.getUnit().getId());
  }

  private static boolean matchesVendor(PropertyExpense expense, UUID vendorId) {
    return expense.getVendor() != null && vendorId.equals(expense.getVendor().getId());
  }

  private static boolean withinDate(PropertyExpense expense, LocalDate from, LocalDate to) {
    LocalDate date = expense.getExpenseDate();
    if (from == null && to == null) return true;
    if (date == null) return false;
    if (from != null && date.isBefore(from)) return false;
    if (to != null && date.isAfter(to)) return false;
    return true;
  }

  private static boolean matchesSearch(PropertyExpense expense, String term) {
    return contains(expense.getExpenseCode(), term)
        || contains(expense.getDescription(), term)
        || contains(expense.getInvoiceReference(), term)
        || contains(
            expense.getProperty() == null ? null : expense.getProperty().getName(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  /** Every month of the window, so the trend chart has an axis with no gaps in it. */
  private static Map<YearMonth, BigDecimal> emptyMonths(LocalDate from, LocalDate to) {
    Map<YearMonth, BigDecimal> months = new TreeMap<>();
    YearMonth cursor = YearMonth.from(from);
    YearMonth end = YearMonth.from(to);
    while (!cursor.isAfter(end)) {
      months.put(cursor, BigDecimal.ZERO);
      cursor = cursor.plusMonths(1);
    }
    return months;
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static <T extends Comparable<? super T>> Comparator<T> nullSafe() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  private static Comparator<PropertyExpense> comparator(Pageable pageable) {
    Comparator<PropertyExpense> tieBreak =
        Comparator.comparing(PropertyExpense::getCreatedAt, nullSafe())
            .thenComparing(PropertyExpense::getId, nullSafe());
    Comparator<PropertyExpense> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<PropertyExpense> by = SORTS.get(order.getProperty());
      if (by == null) continue;
      if (order.isDescending()) by = by.reversed();
      chosen = chosen == null ? by : chosen.thenComparing(by);
    }
    return chosen == null ? tieBreak.reversed() : chosen.thenComparing(tieBreak);
  }

  private static <T> PageResponse<T> paginate(List<T> items, Pageable pageable) {
    int size = pageable == null || pageable.getPageSize() < 1 ? 20 : pageable.getPageSize();
    int page = pageable == null ? 0 : pageable.getPageNumber();
    int from = Math.min(page * size, items.size());
    int to = Math.min(from + size, items.size());
    return PageResponse.of(items.subList(from, to), page, size, items.size());
  }
}
