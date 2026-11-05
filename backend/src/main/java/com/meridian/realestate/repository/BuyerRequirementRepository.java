package com.meridian.realestate.repository;

import com.meridian.realestate.domain.BuyerRequirement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Buyers' briefs.
 *
 * <p>A brief is deactivated rather than deleted when it is superseded, so the history of what a
 * buyer was looking for stays readable - which is the record that explains why a property was or was
 * not shown to them. Only the active briefs are matched against stock; the inactive ones are read
 * as history.</p>
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist.</p>
 */
public interface BuyerRequirementRepository extends JpaRepository<BuyerRequirement, UUID> {

  Optional<BuyerRequirement> findByIdAndDeletedAtIsNull(UUID id);

  Page<BuyerRequirement> findAllByDeletedAtIsNull(Pageable pageable);

  List<BuyerRequirement> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** Every brief this buyer has ever stated, active and superseded. */
  List<BuyerRequirement> findAllByBuyerIdAndDeletedAtIsNull(UUID buyerId);

  /** The briefs a buyer is looking under right now - the ones that may be matched against stock. */
  List<BuyerRequirement> findAllByBuyerIdAndIsActiveTrueAndDeletedAtIsNull(UUID buyerId);

  long countByBuyerIdAndIsActiveTrueAndDeletedAtIsNull(UUID buyerId);

  /**
   * A buyer's briefs with the buyer and the property type fetched.
   *
   * <p>The brief list is always shown against the buyer it belongs to and the category it names, so
   * loading either one row at a time would turn the screen into N+1 queries. Active briefs are
   * listed first, then the most recently stated.</p>
   */
  @Query(
      "select r from BuyerRequirement r "
          + "join fetch r.buyer "
          + "left join fetch r.propertyType "
          + "where r.deletedAt is null and r.buyer.id = :buyerId "
          + "order by r.isActive desc, r.createdAt desc, r.id asc")
  List<BuyerRequirement> findAllForBuyer(@Param("buyerId") UUID buyerId);

  /**
   * Every active brief in the book, for matching a new or changed property against demand.
   *
   * <p>Buyer and property type are fetched because a match is reported as "who wants this", which
   * is a buyer's name and a category. Superseded briefs are excluded at the source: matching
   * against a brief the buyer has moved on from would produce leads nobody can act on.</p>
   */
  @Query(
      "select r from BuyerRequirement r "
          + "join fetch r.buyer "
          + "left join fetch r.propertyType "
          + "where r.deletedAt is null and r.isActive = true "
          + "order by r.createdAt desc, r.id asc")
  List<BuyerRequirement> findAllActiveForMatching();
}
