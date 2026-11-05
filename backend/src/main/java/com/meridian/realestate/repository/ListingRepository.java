package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Listing;
import com.meridian.realestate.domain.ListingStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * The listing board.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} the caller has already validated.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A listing is archived rather than
 * removed.</p>
 */
public interface ListingRepository extends JpaRepository<Listing, UUID> {

  Optional<Listing> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Listing> findByListingCodeIgnoreCaseAndDeletedAtIsNull(String listingCode);

  boolean existsByListingCodeIgnoreCaseAndDeletedAtIsNull(String listingCode);

  Page<Listing> findAllByDeletedAtIsNull(Pageable pageable);

  List<Listing> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** The listings pointing at one property. */
  List<Listing> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  /** The listings pointing at one unit. */
  List<Listing> findAllByUnitIdAndDeletedAtIsNull(UUID unitId);

  long countByStatusAndDeletedAtIsNull(ListingStatus status);

  /**
   * The listings still on the market. The caller passes in the closed statuses read from {@code
   * ck_listings_status} - {@code LET_AGREED}, {@code SOLD}, {@code WITHDRAWN}, {@code EXPIRED} -
   * and everything else is live.
   */
  List<Listing> findAllByStatusNotInAndDeletedAtIsNull(Collection<ListingStatus> closedStatuses);

  /** How many listings are still on the market. */
  long countByStatusNotInAndDeletedAtIsNull(Collection<ListingStatus> closedStatuses);

  /** The listings whose end date has passed - the ones to chase or close. */
  List<Listing> findAllByListingEndBeforeAndDeletedAtIsNull(LocalDate date);

  /**
   * The register, with the associations the list reads eager-fetched so it does not incur a query
   * per row: the property, the unit, the channel and the listing agent.
   */
  @Query(
      "select l from Listing l "
          + "left join fetch l.property "
          + "left join fetch l.unit "
          + "left join fetch l.channel "
          + "left join fetch l.listedBy "
          + "where l.deletedAt is null")
  List<Listing> findAllForRegister();
}
