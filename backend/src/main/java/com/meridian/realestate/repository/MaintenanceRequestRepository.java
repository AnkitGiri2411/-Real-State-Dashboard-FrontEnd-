package com.meridian.realestate.repository;

import com.meridian.realestate.domain.MaintenancePriority;
import com.meridian.realestate.domain.MaintenanceRequest;
import com.meridian.realestate.domain.MaintenanceStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * The maintenance board.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} the caller has already validated.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A request is archived rather than removed,
 * because the expenses recorded against it and the status trail that explains it must stay
 * coherent.</p>
 *
 * <p>The two counts the dashboard leans on - {@link #countByStatusInAndDeletedAtIsNull} and
 * {@link #countByStatusInAndPriorityInAndDeletedAtIsNull} - take their sets from the caller rather
 * than hard-coding them, so the open set and the urgent set each live in one place. They are the
 * predicates of the schema's partial index {@code ix_maintenance_requests_open} and of
 * {@link MaintenancePriority#isUrgent()}; a caller that passes a different set reports a number the
 * board cannot be reconciled with.</p>
 */
public interface MaintenanceRequestRepository extends JpaRepository<MaintenanceRequest, UUID> {

  Optional<MaintenanceRequest> findByIdAndDeletedAtIsNull(UUID id);

  Optional<MaintenanceRequest> findByRequestCodeIgnoreCaseAndDeletedAtIsNull(String requestCode);

  boolean existsByRequestCodeIgnoreCaseAndDeletedAtIsNull(String requestCode);

  Page<MaintenanceRequest> findAllByDeletedAtIsNull(Pageable pageable);

  List<MaintenanceRequest> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** The requests raised against one property - the maintenance tab of the asset. */
  List<MaintenanceRequest> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  /** The requests raised against one unit. */
  List<MaintenanceRequest> findAllByUnitIdAndDeletedAtIsNull(UUID unitId);

  List<MaintenanceRequest> findAllByStatusAndDeletedAtIsNull(MaintenanceStatus status);

  Page<MaintenanceRequest> findAllByStatusAndDeletedAtIsNull(
      MaintenanceStatus status, Pageable pageable);

  List<MaintenanceRequest> findAllByStatusInAndDeletedAtIsNull(
      Collection<MaintenanceStatus> statuses);

  List<MaintenanceRequest> findAllByPriorityAndDeletedAtIsNull(MaintenancePriority priority);

  /** The requests held by one vendor - what the firm is currently waiting on that trade for. */
  List<MaintenanceRequest> findAllByAssignedVendorIdAndDeletedAtIsNull(UUID vendorId);

  /** The open count: the requests still outstanding, over the open set the caller passes in. */
  long countByStatusInAndDeletedAtIsNull(Collection<MaintenanceStatus> statuses);

  /**
   * The urgent-work KPI: open requests whose priority is {@code HIGH} or {@code URGENT}.
   *
   * <p>Both halves matter. A high-priority request that has been resolved is not outstanding work,
   * and an open {@code LOW} request is not urgent - so the figure is the intersection of the open
   * statuses and the urgent priorities, which is exactly what the dashboard card reads.</p>
   */
  long countByStatusInAndPriorityInAndDeletedAtIsNull(
      Collection<MaintenanceStatus> statuses, Collection<MaintenancePriority> priorities);

  /**
   * The maintenance cost report: what the actual spend came to over a period.
   *
   * <p>Summed over {@code reported_at} because that is the moment the fault entered the books and
   * the axis the period is reported on. Rows with no actual cost yet are still included by
   * {@code coalesce}; they contribute nothing, which is correct - an unpriced request is not a cost
   * of zero, it simply has not been priced.</p>
   */
  @Query(
      "select coalesce(sum(r.actualCost), 0) from MaintenanceRequest r "
          + "where r.deletedAt is null and r.reportedAt between :from and :to")
  BigDecimal sumActualCostBetween(
      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  /** The kanban column heads: how many requests sit in each status. */
  @Query(
      "select r.status as status, count(r) as total from MaintenanceRequest r "
          + "where r.deletedAt is null group by r.status")
  List<StatusCount> countGroupedByStatus();

  /** The priority chart: how many requests carry each priority. */
  @Query(
      "select r.priority as priority, count(r) as total from MaintenanceRequest r "
          + "where r.deletedAt is null group by r.priority")
  List<PriorityCount> countGroupedByPriority();

  /**
   * The board, with the associations the list reads eager-fetched so it does not incur a query per
   * row: the property, the unit and the vendor holding the work.
   */
  @Query(
      "select r from MaintenanceRequest r "
          + "left join fetch r.property "
          + "left join fetch r.unit "
          + "left join fetch r.assignedVendor "
          + "where r.deletedAt is null")
  List<MaintenanceRequest> findAllForBoard();

  /** One kanban column: a status and how many live requests are in it. */
  interface StatusCount {

    MaintenanceStatus getStatus();

    long getTotal();
  }

  /** One bar of the priority chart. */
  interface PriorityCount {

    MaintenancePriority getPriority();

    long getTotal();
  }
}
