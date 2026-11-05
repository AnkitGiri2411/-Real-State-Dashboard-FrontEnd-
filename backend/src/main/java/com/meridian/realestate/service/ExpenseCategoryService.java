package com.meridian.realestate.service;

import com.meridian.realestate.domain.ExpenseCategory;
import com.meridian.realestate.dto.ExpenseCategoryDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.ExpenseCategoryRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expense headings, and the one question net income depends on.
 *
 * <p><b>Why this class exists.</b> Every expense is recorded under a heading, and the heading - not
 * the expense row - is what says whether the cost is a cost of the period's letting or an investment
 * in the asset. That distinction is the input to net operating income, and it is a single fact with a
 * single home ({@code expense_categories.is_capital}), so it is read through one service rather than
 * re-decided wherever somebody needs it. Two parts of the product ask the question - the expense
 * ledger, when it splits a period's spend, and any net-income figure - and if they answered it
 * differently the portfolio's running result would be wrong in a way nobody could see, because both
 * halves of the arithmetic are real money.</p>
 *
 * <p><b>The rule it protects.</b> The operating set and the capital set are never returned merged.
 * A caller that received them as one list could sum them, and summing them is the exact mistake the
 * V15 header warns about: <em>net operating income is collected rent less operating expenses, with
 * capital expenditure excluded and reported separately</em>. Netting capital off would overstate cost
 * and understate the portfolio's running result, and the error would be invisible. The split is
 * therefore stated here, and the read offers each side on its own.</p>
 *
 * <p>Nothing here writes. The headings are seeded by the migration chain because every installation
 * needs the same ones, and nobody archives "Property tax"; a category is a fact about what the product
 * understands, not a record a desk maintains.</p>
 */
@Service
public class ExpenseCategoryService {

  private final ExpenseCategoryRepository categories;

  public ExpenseCategoryService(ExpenseCategoryRepository categories) {
    this.categories = categories;
  }

  /** Every heading, in the order the picker offers them. */
  @Transactional(readOnly = true)
  public List<ExpenseCategoryDtos.CategoryView> list() {
    return categories.findAllByOrderByDisplayOrderAsc().stream()
        .map(ExpenseCategoryDtos.CategoryView::from)
        .toList();
  }

  /**
   * The NOI input: the headings a cost may be recorded under and still count towards net operating
   * income. Deliberately its own read rather than a filter over {@link #list()}, so the set the
   * subtraction is made over is stated rather than inferred.
   */
  @Transactional(readOnly = true)
  public List<ExpenseCategoryDtos.CategoryView> operating() {
    return categories.findAllOperating().stream()
        .map(ExpenseCategoryDtos.CategoryView::from)
        .toList();
  }

  /** The investment headings that net operating income excludes and the capital report reads. */
  @Transactional(readOnly = true)
  public List<ExpenseCategoryDtos.CategoryView> capital() {
    return categories.findAllCapital().stream()
        .map(ExpenseCategoryDtos.CategoryView::from)
        .toList();
  }

  /** Both sides of the split, kept apart. */
  @Transactional(readOnly = true)
  public ExpenseCategoryDtos.OperatingCategories split() {
    return new ExpenseCategoryDtos.OperatingCategories(operating(), capital());
  }

  /** The ids in the operating set, for a caller that has to test membership over many rows. */
  @Transactional(readOnly = true)
  public Set<UUID> operatingCategoryIds() {
    return new LinkedHashSet<>(categories.findAllOperating().stream().map(ExpenseCategory::getId).toList());
  }

  /**
   * True when a cost recorded under this heading counts towards net operating income.
   *
   * <p>An unknown id is refused rather than answered false: "no such heading" and "an investment"
   * are different facts, and answering the second when the first is true would silently move money
   * out of the operating figure.</p>
   */
  @Transactional(readOnly = true)
  public boolean isOperating(UUID categoryId) {
    return !require(categoryId).isCapital();
  }

  /** The live heading behind an id, or a 404. */
  @Transactional(readOnly = true)
  public ExpenseCategory require(UUID categoryId) {
    return categories
        .findById(categoryId)
        .orElseThrow(() -> ApiException.notFound("We could not find that expense category"));
  }
}
