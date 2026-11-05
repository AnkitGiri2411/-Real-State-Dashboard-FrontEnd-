package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyStatus;
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
 * The asset register.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist, so
 * the caller's requested order is the one that is actually applied.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A property is archived rather than removed,
 * because the leases, invoices and audit rows that reference it must stay coherent.</p>
 */
public interface PropertyRepository extends JpaRepository<Property, UUID> {

  Optional<Property> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Property> findByPropertyCodeIgnoreCaseAndDeletedAtIsNull(String propertyCode);

  boolean existsByPropertyCodeIgnoreCaseAndDeletedAtIsNull(String propertyCode);

  Page<Property> findAllByDeletedAtIsNull(Pageable pageable);

  List<Property> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  long countByStatusAndDeletedAtIsNull(PropertyStatus status);

  List<Property> findAllByStatusInAndDeletedAtIsNull(Collection<PropertyStatus> statuses);

  /**
   * The properties that can actually be plotted.
   *
   * <p>The map draws only these. A property with no coordinate pair is omitted rather than placed
   * at a guessed point, which is why the filter is part of the query rather than something the
   * caller remembers to apply.</p>
   */
  @Query(
      "select p from Property p "
          + "where p.deletedAt is null and p.latitude is not null and p.longitude is not null")
  List<Property> findAllPositioned();

  /** Total acquisition cost and current valuation, for the portfolio summary. */
  @Query(
      "select coalesce(sum(p.currentValuation), 0) from Property p where p.deletedAt is null")
  java.math.BigDecimal sumCurrentValuation();

  @Query(
      "select coalesce(sum(p.acquisitionValue), 0) from Property p where p.deletedAt is null")
  java.math.BigDecimal sumAcquisitionValue();

  /** The distinct cities the portfolio actually holds stock in, for the filter bar. */
  @Query(
      "select distinct p.city from Property p where p.deletedAt is null order by p.city asc")
  List<String> findDistinctCities();

  /** Properties matching a free-text term across the fields the register is searched by. */
  @Query(
      "select p from Property p "
          + "where p.deletedAt is null and ("
          + "  lower(p.name) like lower(concat('%', :term, '%')) "
          + "  or lower(p.propertyCode) like lower(concat('%', :term, '%')) "
          + "  or lower(p.city) like lower(concat('%', :term, '%')) "
          + "  or lower(coalesce(p.locality, '')) like lower(concat('%', :term, '%'))"
          + ")")
  Page<Property> search(@Param("term") String term, Pageable pageable);

  /** The same search, narrowed to one city. */
  @Query(
      "select p from Property p "
          + "where p.deletedAt is null and p.city = :city and ("
          + "  lower(p.name) like lower(concat('%', :term, '%')) "
          + "  or lower(p.propertyCode) like lower(concat('%', :term, '%')) "
          + "  or lower(coalesce(p.locality, '')) like lower(concat('%', :term, '%'))"
          + ")")
  Page<Property> searchInCity(
      @Param("city") String city, @Param("term") String term, Pageable pageable);
}
