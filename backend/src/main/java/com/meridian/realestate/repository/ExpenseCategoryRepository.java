package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ExpenseCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Lookup access to the headings an expense is recorded under. Reference data: read-only in the app.
 *
 * <p>The operating-versus-capital split is read from here, not from an expense row. A caller that
 * asks whether a cost counts towards net operating income asks the category, which is why the reads
 * below are the ones the NOI calculation actually makes.</p>
 */
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

  Optional<ExpenseCategory> findByCode(String code);

  Optional<ExpenseCategory> findByCodeIgnoreCase(String code);

  /** The categories in the order the picker offers them. */
  List<ExpenseCategory> findAllByOrderByDisplayOrderAsc();

  /**
   * The operating categories - the set net operating income is computed over.
   *
   * <p><b>This is the NOI input.</b> Everything recorded under one of these headings is a cost of the
   * period's letting; everything under a capital heading is excluded deliberately and reported
   * separately. See {@link ExpenseCategory}.</p>
   */
  @Query(
      "select c from ExpenseCategory c where c.capital = false order by c.displayOrder asc")
  List<ExpenseCategory> findAllOperating();

  /**
   * The capital categories - the investment set that NOI excludes and the capital report reads.
   */
  @Query("select c from ExpenseCategory c where c.capital = true order by c.displayOrder asc")
  List<ExpenseCategory> findAllCapital();
}
