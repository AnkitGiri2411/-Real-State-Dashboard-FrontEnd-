package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.UnitStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The unit register - the busiest read in the product.
 *
 * <p>The unit board groups by building and then orders by floor and unit number; occupancy counts
 * the units where {@code leasable = true}, by status. Both shapes are answered directly here so the
 * callers do not assemble them by hand.</p>
 *
 * <p>No method here carries a literal {@code order by} inside a paged {@code @Query}: paging and
 * sorting arrive as a {@link Pageable} that {@code PageRequestFactory} has already validated
 * against a whitelist, so the caller's requested order is the one that is actually applied. The
 * deterministic floor / unit-number orderings are plain derived queries, which are not paged.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A unit is archived rather than removed,
 * because the leases, invoices and audit rows that reference it must stay coherent.</p>
 */
public interface UnitRepository extends JpaRepository<Unit, UUID> {

  Optional<Unit> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Unit> findByUnitCodeIgnoreCaseAndDeletedAtIsNull(String unitCode);

  boolean existsByUnitCodeIgnoreCaseAndDeletedAtIsNull(String unitCode);

  /** A unit number is unique within its property rather than globally; see {@code uq_units_property_number}. */
  boolean existsByPropertyIdAndUnitNumberIgnoreCaseAndDeletedAtIsNull(
      UUID propertyId, String unitNumber);

  Page<Unit> findAllByDeletedAtIsNull(Pageable pageable);

  List<Unit> findAllByDeletedAtIsNull();

  Page<Unit> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId, Pageable pageable);

  List<Unit> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  /**
   * The unit board for one property, ordered by floor then unit number.
   *
   * <p>The board is read as a stack of floors, so the order is part of the query rather than
   * something the view re-sorts after the fact.</p>
   */
  List<Unit> findAllByPropertyIdAndDeletedAtIsNullOrderByFloorNumberAscUnitNumberAsc(
      UUID propertyId);

  /** The board for one block within a property - the grouping the unit board actually shows. */
  List<Unit>
      findAllByPropertyIdAndBuildingIdAndDeletedAtIsNullOrderByFloorNumberAscUnitNumberAsc(
          UUID propertyId, UUID buildingId);

  List<Unit> findAllByBuildingIdAndDeletedAtIsNullOrderByFloorNumberAscUnitNumberAsc(
      UUID buildingId);

  long countByDeletedAtIsNull();

  long countByStatusAndDeletedAtIsNull(UnitStatus status);

  long countByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  long countByBuildingIdAndDeletedAtIsNull(UUID buildingId);

  /** The occupancy denominator: every unit that could be let. */
  long countByLeasableTrueAndDeletedAtIsNull();

  /** The occupancy numerator, for one status, across the whole portfolio. */
  long countByLeasableTrueAndStatusAndDeletedAtIsNull(UnitStatus status);

  /** The occupancy denominator for one property. */
  long countByPropertyIdAndLeasableTrueAndDeletedAtIsNull(UUID propertyId);

  /** The occupancy numerator for one property and one status. */
  long countByPropertyIdAndLeasableTrueAndStatusAndDeletedAtIsNull(
      UUID propertyId, UnitStatus status);

  /**
   * The monthly rent asked across a property's lettable stock.
   *
   * <p>Filtered exactly as occupancy is - leasable and not deleted - so the headline rent and the
   * occupancy percentage are computed over the same set of units. Units with no asking rent
   * contribute zero rather than dropping the property from the figure.</p>
   */
  @Query(
      "select coalesce(sum(u.askingRent), 0) from Unit u "
          + "where u.deletedAt is null and u.leasable = true and u.property.id = :propertyId")
  BigDecimal sumAskingRentByProperty(@Param("propertyId") UUID propertyId);
}
