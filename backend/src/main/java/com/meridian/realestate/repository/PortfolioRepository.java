package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Portfolio;
import com.meridian.realestate.domain.PortfolioStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The books of assets.
 *
 * <p>A portfolio is archived rather than deleted, so every read filters on {@code deletedAt is
 * null}: the allocations, reports and ownership history that reference a closed book must stay
 * coherent, and a book that vanished would take its composition with it.</p>
 *
 * <p>No paged method here carries a literal {@code order by}; the sort is the caller's, validated
 * before it reaches this interface.</p>
 */
public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {

  Optional<Portfolio> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Portfolio> findByPortfolioCodeIgnoreCaseAndDeletedAtIsNull(String portfolioCode);

  boolean existsByPortfolioCodeIgnoreCaseAndDeletedAtIsNull(String portfolioCode);

  Page<Portfolio> findAllByDeletedAtIsNull(Pageable pageable);

  List<Portfolio> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  List<Portfolio> findAllByStatusAndDeletedAtIsNull(PortfolioStatus status);

  long countByStatusAndDeletedAtIsNull(PortfolioStatus status);

  long countByStatusInAndDeletedAtIsNull(Collection<PortfolioStatus> statuses);

  /**
   * The book list with both of its people fetched.
   *
   * <p>Owner and manager are fetched together because a portfolio row renders both or neither, and
   * fetching them separately would be two extra round trips for a column of names. This query is not
   * paged on purpose: a fetch join over two associations with a page size would have to be applied
   * in memory, which is exactly the silent mis-paging this interface avoids.</p>
   */
  @Query(
      "select p from Portfolio p "
          + "left join fetch p.owner left join fetch p.manager "
          + "where p.deletedAt is null")
  List<Portfolio> findAllWithOwnerAndManager();

  @Query(
      "select p from Portfolio p "
          + "left join fetch p.owner left join fetch p.manager "
          + "where p.id = :id")
  Optional<Portfolio> findByIdWithOwnerAndManager(@Param("id") UUID id);

  /** The books one owner holds, for the owner's own report. */
  List<Portfolio> findAllByOwnerIdAndDeletedAtIsNull(UUID ownerId);

  /** The books one manager runs. */
  List<Portfolio> findAllByManagerIdAndDeletedAtIsNull(UUID managerId);
}
