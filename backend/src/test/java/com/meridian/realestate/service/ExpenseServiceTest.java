package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.ExpenseCategory;
import com.meridian.realestate.domain.ExpenseGroup;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyExpense;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ExpenseDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.PropertyExpenseRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.VendorRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The expense ledger's arithmetic and its two halves, asserted without a database.
 *
 * <p>Three promises are pinned here, and each is a rule a real ledger gets wrong in a way nobody can
 * see. <b>The total is always {@code amount + tax}</b>, computed server-side at the product's money
 * scale - a total supplied by the caller is ignored, because a stored row whose printed parts do not
 * sum is an invoice nobody can reconcile. <b>A recovery is bounded by what the cost came to and gated
 * by the lease</b>, so nothing can be clawed back beyond the cost and nothing can be clawed back at
 * all from a cost the lease does not allow to be recharged. And <b>net operating income excludes
 * capital expenditure</b> - the operating figure is the cost of the period's letting, capital is
 * reported separately, and the two are never added together, because summing them would overstate cost
 * and understate the portfolio's running result in a way that is invisible from the outside.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExpenseService: the total, the recovery rules and the operating/capital split")
class ExpenseServiceTest {

  @Mock private PropertyExpenseRepository expenses;
  @Mock private ExpenseCategoryService expenseCategories;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private MaintenanceRequestRepository maintenanceRequests;
  @Mock private VendorRepository vendors;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private ExpenseService service;

  @BeforeEach
  void setUp() {
    AppProperties appProperties = new AppProperties();
    appProperties.getPortfolio().setTrendMonths(6);

    service =
        new ExpenseService(
            expenses,
            expenseCategories,
            properties,
            units,
            maintenanceRequests,
            vendors,
            numbers,
            audit,
            accessScope,
            appProperties);
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Deepa");
    user.setLastName("Venkatesan");
    return user;
  }

  private static Property property() {
    Property property = new Property();
    property.setId(UUID.randomUUID());
    property.setPropertyCode("ME-P-00001");
    property.setName("Meridian House");
    return property;
  }

  private static ExpenseCategory category(boolean capital, boolean typicallyRecoverable) {
    ExpenseCategory category = new ExpenseCategory();
    category.setId(UUID.randomUUID());
    category.setCode(capital ? "CAPITAL_REPLACEMENT" : "REPAIRS_PLUMBING");
    category.setName(capital ? "Plant replacement" : "Plumbing repairs");
    category.setGroupName(capital ? ExpenseGroup.CAPITAL : ExpenseGroup.REPAIR);
    category.setCapital(capital);
    category.setTypicallyRecoverable(typicallyRecoverable);
    return category;
  }

  private static PropertyExpense expense(
      ExpenseCategory category, String amount, String tax, boolean recoverable, String recovered) {
    PropertyExpense expense = new PropertyExpense();
    expense.setId(UUID.randomUUID());
    expense.setExpenseCode("ME-M-00001");
    expense.setProperty(property());
    expense.setCategory(category);
    expense.setExpenseDate(LocalDate.now());
    expense.setAmount(new BigDecimal(amount));
    expense.setTaxAmount(new BigDecimal(tax));
    expense.setTotalAmount(new BigDecimal(amount).add(new BigDecimal(tax)));
    expense.setDescription("Repair work");
    expense.setRecoverable(recoverable);
    expense.setRecoveredAmount(new BigDecimal(recovered));
    return expense;
  }

  private void stubActor() {
    lenient().when(accessScope.requireUser()).thenReturn(actor());
  }

  private void stubCreation(ExpenseCategory category) {
    lenient().when(properties.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(property()));
    lenient().when(expenseCategories.require(category.getId())).thenReturn(category);
    lenient().when(numbers.next(BusinessNumberService.MAINTENANCE)).thenReturn("ME-M-00042");
    lenient().when(expenses.save(any(PropertyExpense.class))).thenAnswer(call -> call.getArgument(0));
  }

  // ---------------------------------------------------------------------------
  // The arithmetic
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("the total is always amount plus tax, and a client-supplied total is ignored")
  void theTotalIsAlwaysAmountPlusTax() {
    ExpenseCategory category = category(false, false);
    stubActor();
    stubCreation(category);

    service.create(
        new ExpenseDtos.CreateExpenseRequest(
            UUID.randomUUID(),
            null,
            category.getId(),
            null,
            null,
            LocalDate.now(),
            null,
            null,
            new BigDecimal("1000.00"),
            new BigDecimal("180.00"),
            "Plumbing repairs",
            "INV-1",
            null,
            null,
            // A deliberate lie: the stored total is the sum of the parts, never this.
            new BigDecimal("1.00")));

    ArgumentCaptor<PropertyExpense> saved = ArgumentCaptor.forClass(PropertyExpense.class);
    verify(expenses).save(saved.capture());

    assertThat(saved.getValue().getTotalAmount()).isEqualByComparingTo("1180.00");
    assertThat(saved.getValue().getTotalAmount().scale()).isEqualTo(2);
    assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1000.00");
    assertThat(saved.getValue().getTaxAmount()).isEqualByComparingTo("180.00");
  }

  @Test
  @DisplayName("money is two decimal places, rounded HALF_UP, at every part and in the total")
  void moneyIsRoundedHalfUpAtTheProductScale() {
    ExpenseCategory category = category(false, false);
    stubActor();
    stubCreation(category);

    service.create(
        new ExpenseDtos.CreateExpenseRequest(
            UUID.randomUUID(),
            null,
            category.getId(),
            null,
            null,
            LocalDate.now(),
            null,
            null,
            new BigDecimal("999.995"),
            new BigDecimal("0.005"),
            "Plumbing repairs",
            null,
            null,
            null,
            null));

    ArgumentCaptor<PropertyExpense> saved = ArgumentCaptor.forClass(PropertyExpense.class);
    verify(expenses).save(saved.capture());

    // Each part is rounded on its own - a raw sum would print a total that disagrees with its lines.
    assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1000.00");
    assertThat(saved.getValue().getTaxAmount()).isEqualByComparingTo("0.01");
    assertThat(saved.getValue().getTotalAmount()).isEqualByComparingTo("1000.01");
    assertThat(saved.getValue().getTotalAmount().scale()).isEqualTo(2);
  }

  @Test
  @DisplayName("changing the amount recomputes the total, and a supplied total is still ignored")
  void updateRecomputesTheTotal() {
    ExpenseCategory category = category(false, false);
    PropertyExpense existing = expense(category, "100.00", "18.00", false, "0.00");

    stubActor();
    when(expenses.findByIdAndDeletedAtIsNull(existing.getId())).thenReturn(Optional.of(existing));
    when(expenses.save(any(PropertyExpense.class))).thenAnswer(call -> call.getArgument(0));

    service.update(
        existing.getId(),
        new ExpenseDtos.UpdateExpenseRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("200.00"),
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("999.00")));

    assertThat(existing.getAmount()).isEqualByComparingTo("200.00");
    assertThat(existing.getTaxAmount()).isEqualByComparingTo("18.00");
    assertThat(existing.getTotalAmount()).isEqualByComparingTo("218.00");
  }

  // ---------------------------------------------------------------------------
  // Recovery
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("recovery above the total is refused")
  void recoveryAboveTheTotalIsRefused() {
    ExpenseCategory category = category(false, true);
    PropertyExpense existing = expense(category, "1000.00", "180.00", true, "0.00");

    stubActor();
    when(expenses.findByIdAndDeletedAtIsNull(existing.getId())).thenReturn(Optional.of(existing));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.recordRecovery(
                    existing.getId(),
                    new ExpenseDtos.RecordRecoveryRequest(new BigDecimal("1180.01"))),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("EXPENSE_RECOVERY_EXCEEDS_TOTAL");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(expenses, never()).save(any());
  }

  @Test
  @DisplayName("recovery exactly at the total is allowed - the bound is inclusive")
  void recoveryAtTheTotalIsAllowed() {
    ExpenseCategory category = category(false, true);
    PropertyExpense existing = expense(category, "1000.00", "180.00", true, "0.00");

    stubActor();
    when(expenses.findByIdAndDeletedAtIsNull(existing.getId())).thenReturn(Optional.of(existing));
    when(expenses.save(any(PropertyExpense.class))).thenAnswer(call -> call.getArgument(0));

    service.recordRecovery(
        existing.getId(), new ExpenseDtos.RecordRecoveryRequest(new BigDecimal("1180.00")));

    assertThat(existing.getRecoveredAmount()).isEqualByComparingTo("1180.00");
    assertThat(existing.getRecoveredAmount().scale()).isEqualTo(2);
  }

  @Test
  @DisplayName("recovery on a non-recoverable expense is refused")
  void recoveryOnANonRecoverableExpenseIsRefused() {
    ExpenseCategory category = category(false, false);
    PropertyExpense existing = expense(category, "1000.00", "0.00", false, "0.00");

    stubActor();
    when(expenses.findByIdAndDeletedAtIsNull(existing.getId())).thenReturn(Optional.of(existing));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.recordRecovery(
                    existing.getId(), new ExpenseDtos.RecordRecoveryRequest(new BigDecimal("250.00"))),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("EXPENSE_RECOVERY_NOT_ALLOWED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(expenses, never()).save(any());
  }

  @Test
  @DisplayName("a recovery of zero is an ordinary correction and is allowed on any expense")
  void aZeroRecoveryIsAllowedOnANonRecoverableExpense() {
    ExpenseCategory category = category(false, false);
    PropertyExpense existing = expense(category, "1000.00", "0.00", false, "0.00");

    stubActor();
    when(expenses.findByIdAndDeletedAtIsNull(existing.getId())).thenReturn(Optional.of(existing));
    when(expenses.save(any(PropertyExpense.class))).thenAnswer(call -> call.getArgument(0));

    service.recordRecovery(
        existing.getId(), new ExpenseDtos.RecordRecoveryRequest(BigDecimal.ZERO));

    assertThat(existing.getRecoveredAmount()).isEqualByComparingTo("0.00");
  }

  // ---------------------------------------------------------------------------
  // The split
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("the operating summary excludes capital expenditure and reports it separately")
  void theOperatingSummaryExcludesCapitalExpenses() {
    ExpenseCategory operating = category(false, false);
    ExpenseCategory operatingRecoverable = category(false, true);
    ExpenseCategory capital = category(true, false);

    PropertyExpense plumbing = expense(operating, "1000.00", "0.00", false, "0.00");
    PropertyExpense housekeeping = expense(operatingRecoverable, "500.00", "0.00", true, "200.00");
    PropertyExpense lift = expense(capital, "5000.00", "0.00", false, "0.00");

    LocalDate today = LocalDate.now();
    when(expenses.findAllForLedger()).thenReturn(List.of(plumbing, housekeeping, lift));

    ExpenseDtos.ExpenseSummaryResponse summary = service.summary(today.withDayOfMonth(1), today);

    // The operating figure is the cost of the period's letting: the lift is not in it.
    assertThat(summary.operatingTotal()).isEqualByComparingTo("1500.00");
    assertThat(summary.operatingTotal().scale()).isEqualTo(2);
    // Capital is reported on its own, and never netted off inside the operating figure.
    assertThat(summary.capitalTotal()).isEqualByComparingTo("5000.00");
    assertThat(summary.operatingTotal()).isNotEqualByComparingTo(summary.totalIncurred());
    assertThat(summary.totalIncurred()).isEqualByComparingTo("6500.00");
    assertThat(summary.operatingCount()).isEqualTo(2);
    assertThat(summary.capitalCount()).isEqualTo(1);

    // The entitlement and the recovery are different figures and are never conflated.
    assertThat(summary.recoverableCount()).isEqualTo(1);
    assertThat(summary.recoveredTotal()).isEqualByComparingTo("200.00");
    assertThat(summary.unrecoveredTotal()).isEqualByComparingTo("6300.00");

    // The trend keeps the two apart month by month, so a chart cannot add them by accident.
    assertThat(summary.monthlyTrend()).hasSize(1);
    assertThat(summary.monthlyTrend().get(0).operatingTotal()).isEqualByComparingTo("1500.00");
    assertThat(summary.monthlyTrend().get(0).capitalTotal()).isEqualByComparingTo("5000.00");
    assertThat(summary.monthlyTrend().get(0).recoveredTotal()).isEqualByComparingTo("200.00");
  }
}
