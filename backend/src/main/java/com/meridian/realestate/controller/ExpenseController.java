package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.ExpenseDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.ExpenseService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The expense ledger: what the portfolio costs to hold.
 *
 * <p><b>Why this controller exists.</b> A cost is recorded once and read from several directions - by
 * property, by unit, by heading, by vendor, by the fault that caused it - so it has one address and
 * one register rather than a maintenance screen and an accounts screen that disagree. This class only
 * routes: the arithmetic, the recovery rules and the operating-versus-capital split are decided in
 * {@link ExpenseService} and raised as an {@link com.meridian.realestate.exception.ApiException},
 * which {@code GlobalExceptionHandler} turns into the product's one error envelope. No entity and no
 * repository appears here.</p>
 *
 * <p><b>Two things this controller deliberately does not accept.</b> There is no field anywhere in it
 * that sets a total - the stored total is always {@code amount + tax_amount}, computed by the
 * service - and there is no field that sets whether a cost is operating or capital, because that is
 * read from the expense's category. Both are properties of the row rather than requests, and letting
 * either in would let net operating income say anything.</p>
 *
 * <p><b>Every endpoint carries a permission code that exists in the {@code V2} seed</b>:
 * {@code EXPENSE_VIEW}, {@code EXPENSE_CREATE}, {@code EXPENSE_UPDATE} and {@code EXPENSE_DELETE}.
 * Archiving is routed through its own endpoint under {@code EXPENSE_DELETE}, so removing a cost from
 * the ledger is a distinct capability from changing one. No role name appears here, so changing what a
 * role may do is a data change and never a code change.</p>
 *
 * <p>Sorting is honoured through {@link PageRequestFactory}: a caller may sort by the fields the
 * ledger offers and by nothing else, and an unsupported field is answered with a 400 that names the
 * accepted ones rather than a 500.</p>
 */
@RestController
@RequestMapping("/api/v1/expenses")
public class ExpenseController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("expenseCode", "expenseCode")
          .allow("expenseDate", "expenseDate")
          .allow("totalAmount", "totalAmount")
          .allow("amount", "amount")
          .allow("category", "category.name")
          .allow("property", "property.name")
          .allow("vendor", "vendor.name")
          .build();

  private final ExpenseService expenseService;

  public ExpenseController(ExpenseService expenseService) {
    this.expenseService = expenseService;
  }

  /**
   * The ledger, filtered by property, unit, heading, operating-or-capital, vendor and date, with
   * free-text search.
   *
   * <p>{@code capital} is a filter rather than a field: {@code false} narrows the register to the
   * operating costs the running result is drawn from, {@code true} to the capital expenditure net
   * operating income excludes. Leaving it out shows both, which is what a ledger is for.</p>
   */
  @GetMapping
  @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
  public ApiResponse<PageResponse<ExpenseDtos.ExpenseSummary>> list(
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) Boolean capital,
      @RequestParam(required = false) UUID vendorId,
      @RequestParam(required = false) UUID maintenanceRequestId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        expenseService.list(
            propertyId,
            unitId,
            categoryId,
            capital,
            vendorId,
            maintenanceRequestId,
            from,
            to,
            search,
            PAGES.toPageable(page, size, sort)));
  }

  /**
   * The period's spend, with operating and capital reported separately.
   *
   * <p>{@code operatingTotal} is the figure net operating income subtracts; capital expenditure is
   * <b>not</b> in it and is returned on its own. See {@link ExpenseService} for why the two are never
   * added into one number.</p>
   */
  @GetMapping("/summary")
  @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
  public ApiResponse<ExpenseDtos.ExpenseSummaryResponse> summary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(expenseService.summary(from, to));
  }

  /** One cost's full record, including the fault it came from. */
  @GetMapping("/{expenseId}")
  @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
  public ApiResponse<ExpenseDtos.ExpenseDetail> detail(@PathVariable UUID expenseId) {
    return ApiResponse.ok(expenseService.detail(expenseId));
  }

  /** Record a cost. The total is computed as {@code amount + tax_amount}, never supplied. */
  @PostMapping
  @PreAuthorize("hasAuthority('EXPENSE_CREATE')")
  public ApiResponse<ExpenseDtos.ExpenseDetail> create(
      @Valid @RequestBody ExpenseDtos.CreateExpenseRequest request) {
    return ApiResponse.ok(expenseService.create(request), "Expense recorded");
  }

  /** Change a cost. A moved amount or tax recomputes the total. */
  @PutMapping("/{expenseId}")
  @PreAuthorize("hasAuthority('EXPENSE_UPDATE')")
  public ApiResponse<ExpenseDtos.ExpenseDetail> update(
      @PathVariable UUID expenseId, @Valid @RequestBody ExpenseDtos.UpdateExpenseRequest request) {
    return ApiResponse.ok(expenseService.update(expenseId, request));
  }

  /** Record what was actually recharged against a recoverable cost. */
  @PostMapping("/{expenseId}/recovery")
  @PreAuthorize("hasAuthority('EXPENSE_UPDATE')")
  public ApiResponse<ExpenseDtos.ExpenseDetail> recordRecovery(
      @PathVariable UUID expenseId, @Valid @RequestBody ExpenseDtos.RecordRecoveryRequest request) {
    return ApiResponse.ok(
        expenseService.recordRecovery(expenseId, request), "Recovery recorded");
  }

  /** Archive a cost: a soft delete, so the period statements built from it stay reconcilable. */
  @PostMapping("/{expenseId}/archive")
  @PreAuthorize("hasAuthority('EXPENSE_DELETE')")
  public ApiResponse<ExpenseDtos.ExpenseDetail> archive(@PathVariable UUID expenseId) {
    return ApiResponse.ok(expenseService.archive(expenseId), "Expense archived");
  }
}
