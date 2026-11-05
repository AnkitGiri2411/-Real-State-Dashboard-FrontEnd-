package com.meridian.realestate.dto;

import com.meridian.realestate.domain.ExpenseCategory;
import java.util.List;
import java.util.UUID;

/**
 * The expense-category reference list.
 *
 * <p><b>Why this shape exists.</b> The expense form needs the headings the product understands -
 * plumbing repairs, property tax, a plant replacement - in the order costs are actually thought
 * about rather than alphabetically, and it needs the two facts a caller cannot derive: whether the
 * heading is capital, and whether that kind of cost is normally rechargeable under a lease.</p>
 *
 * <p><b>{@code capital} is published deliberately.</b> It is not a label on a heading, it is the flag
 * that decides whether a cost enters net operating income. The read exposes it so a form can show
 * which side of that line a cost is about to fall on, rather than letting a screen imply the split is
 * a matter of taste. The companion {@link OperatingCategories} is what the net-operating-income
 * figure is computed over: everything recorded under one of those headings is a cost of the period's
 * letting, and everything else is excluded and reported separately. See {@link ExpenseCategory}.</p>
 */
public final class ExpenseCategoryDtos {

  private ExpenseCategoryDtos() {}

  /** One heading an expense may be recorded under. */
  public record CategoryView(
      UUID id,
      String code,
      String name,
      String group,
      /** True for capital expenditure, which net operating income excludes. */
      boolean capital,
      /** True where this kind of cost is normally recoverable from a tenant under a lease. */
      boolean typicallyRecoverable,
      String description,
      int displayOrder) {

    public static CategoryView from(ExpenseCategory category) {
      return new CategoryView(
          category.getId(),
          category.getCode(),
          category.getName(),
          category.getGroupName() == null ? null : category.getGroupName().name(),
          category.isCapital(),
          category.isTypicallyRecoverable(),
          category.getDescription(),
          category.getDisplayOrder());
    }
  }

  /**
   * The two sides of the split, kept apart.
   *
   * <p>They are returned together and never as one list, because a caller that received them merged
   * could add them up, and adding them up is the single mistake this whole distinction exists to
   * prevent.</p>
   */
  public record OperatingCategories(
      /** The headings net operating income is computed over. */
      List<CategoryView> operating,
      /** The headings excluded from net operating income and reported separately. */
      List<CategoryView> capital) {

    public List<UUID> operatingIds() {
      return operating.stream().map(CategoryView::id).toList();
    }
  }
}
