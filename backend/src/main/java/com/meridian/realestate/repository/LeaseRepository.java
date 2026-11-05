package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.LeaseStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The leases - the spine of the rent side of the product.
 *
 * <p>No paged query here carries a literal {@code order by}: the caller's order arrives as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A lease is archived rather than removed,
 * because the invoices and allocations that reference it must stay coherent.</p>
 */
public interface LeaseRepository extends JpaRepository<Lease, UUID> {

  Optional<Lease> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Lease> findByLeaseCodeIgnoreCaseAndDeletedAtIsNull(String leaseCode);

  boolean existsByLeaseCodeIgnoreCaseAndDeletedAtIsNull(String leaseCode);

  Page<Lease> findAllByDeletedAtIsNull(Pageable pageable);

  List<Lease> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** How many leases sit in one state - the status breakdown and the "needs attention" rail. */
  long countByStatusAndDeletedAtIsNull(LeaseStatus status);

  /** The leases of one tenant. */
  Page<Lease> findAllByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);

  /** The leases of one property. */
  Page<Lease> findAllByProperty_IdAndDeletedAtIsNull(UUID propertyId, Pageable pageable);

  /** The leases of one unit, across time - the unit's letting history. */
  Page<Lease> findAllByUnitIdAndDeletedAtIsNull(UUID unitId, Pageable pageable);

  /**
   * The lease currently in force on a unit - the {@code uq_leases_one_running_per_unit} rule.
   *
   * <p>At most one lease per unit can be {@code ACTIVE} or {@code EXPIRING}; the partial unique
   * index guarantees it, which is why this can return a single result. Occupancy is therefore a fact
   * read from this method rather than an inference over the lease list.</p>
   */
  @Query(
      "select l from Lease l "
          + "where l.deletedAt is null and l.unitId = :unitId "
          + "and l.status in (com.meridian.realestate.domain.LeaseStatus.ACTIVE, "
          + "com.meridian.realestate.domain.LeaseStatus.EXPIRING)")
  Optional<Lease> findRunningLeaseForUnit(@Param("unitId") UUID unitId);

  /**
   * The expiry report: running leases whose end date falls in a window.
   *
   * <p>Only {@code ACTIVE} and {@code EXPIRING} leases are candidates - a lease that has already
   * ended is not "expiring". Sorted by the caller's {@link Pageable}, typically end date ascending.
   * </p>
   */
  @Query(
      "select l from Lease l "
          + "where l.deletedAt is null "
          + "and l.status in (com.meridian.realestate.domain.LeaseStatus.ACTIVE, "
          + "com.meridian.realestate.domain.LeaseStatus.EXPIRING) "
          + "and l.endDate between :from and :to")
  Page<Lease> findExpiringBetween(
      @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

  /**
   * The monthly rent roll: the sum of the headline rent over the running leases.
   *
   * <p>This is the contractual rent as billed at each lease's own {@code rent_frequency}. A figure
   * that must be strictly monthly normalises each lease by
   * {@link com.meridian.realestate.domain.RentFrequency#monthsPerPeriod()}; the raw sum is what the
   * portfolio-level rent roll shows.</p>
   */
  @Query(
      "select coalesce(sum(l.rentAmount), 0) from Lease l "
          + "where l.deletedAt is null "
          + "and l.status in (com.meridian.realestate.domain.LeaseStatus.ACTIVE, "
          + "com.meridian.realestate.domain.LeaseStatus.EXPIRING)")
  BigDecimal sumRunningRentAmount();

  /** The count of leases in each state, for the status breakdown. */
  @Query(
      "select l.status as status, count(l) as total from Lease l "
          + "where l.deletedAt is null group by l.status")
  List<StatusCount> countGroupedByStatus();

  /** One state and how many live leases are in it. */
  interface StatusCount {

    LeaseStatus getStatus();

    long getTotal();
  }
}
