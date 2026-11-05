package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PortfolioProperty;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Portfolio allocation: which property is in which book, from when to when.
 *
 * <p>An allocation is a period, not a flag, so "the current members of this book" always means
 * {@code deallocatedAt is null} and never merely "a row exists". The schema enforces the same
 * reading with a partial unique index over the open rows, which is why an open allocation for a
 * property is looked up as a single value.</p>
 *
 * <p>History is kept rather than overwritten, so a portfolio's composition at a past date stays
 * reconstructable - which is why a property's rows are returned as a list even though at most one
 * of them can be open.</p>
 */
public interface PortfolioPropertyRepository extends JpaRepository<PortfolioProperty, UUID> {

  Optional<PortfolioProperty> findByIdAndDeletedAtIsNull(UUID id);

  /**
   * The properties currently in one book.
   *
   * <p>Closed allocations are excluded by design: an asset that left the book is history, not a
   * member, and including it would overstate what the portfolio holds today.</p>
   */
  List<PortfolioProperty> findAllByPortfolioIdAndDeallocatedAtIsNullAndDeletedAtIsNull(
      UUID portfolioId);

  Page<PortfolioProperty> findAllByPortfolioIdAndDeallocatedAtIsNullAndDeletedAtIsNull(
      UUID portfolioId, Pageable pageable);

  /** Every book the property has ever been allocated to, open period first is the caller's sort. */
  List<PortfolioProperty> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  /**
   * The property's open allocation, if it has one.
   *
   * <p>Single-valued because the schema makes it so: a property is never in two open allocations at
   * once, since that would double-count it in every portfolio total silently.</p>
   */
  Optional<PortfolioProperty> findByPropertyIdAndDeallocatedAtIsNullAndDeletedAtIsNull(
      UUID propertyId);

  /** True when the property is in the book right now, which is the condition for a transfer. */
  boolean existsByPropertyIdAndDeallocatedAtIsNullAndDeletedAtIsNull(UUID propertyId);

  long countByPortfolioIdAndDeallocatedAtIsNullAndDeletedAtIsNull(UUID portfolioId);

  /**
   * How many assets each book currently holds, in one round trip.
   *
   * <p>Each row is {@code [portfolioId, Long]}. Counting open allocations only - a closed period is
   * not a holding - and grouping in the database rather than by loading every row, because the
   * portfolio list needs the number for every book at once.</p>
   */
  @Query(
      "select pp.portfolio.id, count(pp) from PortfolioProperty pp "
          + "where pp.deallocatedAt is null and pp.deletedAt is null "
          + "group by pp.portfolio.id")
  List<Object[]> countOpenAllocationsByPortfolio();
}
