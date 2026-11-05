package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.ExpenseCategoryDtos;
import com.meridian.realestate.service.ExpenseCategoryService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The expense headings, and the operating-only filter net income is drawn from.
 *
 * <p><b>Why this controller exists.</b> The expense form has to offer the headings the product
 * understands - plumbing repairs, property tax, a plant replacement - in the order costs are actually
 * thought about, and it has to show which of them are capital. Both are read-only: a heading is seeded
 * by the schema migration because every installation needs the same ones, and nobody archives
 * "Property tax".</p>
 *
 * <p><b>Why {@code operatingOnly} is a parameter and not a separate endpoint.</b> The two sides of the
 * split are the same list seen twice, and a caller that has to know which address to ask for one side
 * is a caller that can get it wrong. {@code GET /expense-categories?operatingOnly=true} answers the
 * question net operating income actually asks - "which headings count as a cost of the period's
 * letting" - and the service answers it from its own read rather than by filtering here, so the set
 * the subtraction is made over is stated once.</p>
 *
 * <p>The read is guarded by {@code EXPENSE_VIEW}, the permission that lets a caller see the ledger the
 * headings classify; the {@code V2} seed has no separate code for reference data, and inventing one
 * would be a permission no role holds. No role name appears here, so changing what a role may do is a
 * data change and never a code change.</p>
 */
@RestController
public class ExpenseCategoryController {

  private final ExpenseCategoryService expenseCategoryService;

  public ExpenseCategoryController(ExpenseCategoryService expenseCategoryService) {
    this.expenseCategoryService = expenseCategoryService;
  }

  /**
   * The expense headings.
   *
   * @param operatingOnly when true, only the headings net operating income is computed over; when
   *     false or absent, every heading, with the capital flag published so a form can show which side
   *     of the line a cost is about to fall on
   */
  @GetMapping("/api/v1/expense-categories")
  @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
  public ApiResponse<List<ExpenseCategoryDtos.CategoryView>> list(
      @RequestParam(required = false) Boolean operatingOnly) {
    return ApiResponse.ok(
        Boolean.TRUE.equals(operatingOnly)
            ? expenseCategoryService.operating()
            : expenseCategoryService.list());
  }
}
