package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Viewing;
import com.meridian.realestate.domain.ViewingStatus;
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
 * The viewing diary.
 *
 * <p>No paged method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} the caller has already validated. The two {@code @Query} methods that do order
 * return plain lists and cannot be paged, so the order is part of the query rather than the
 * caller's choice.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}.</p>
 */
public interface ViewingRepository extends JpaRepository<Viewing, UUID> {

  Optional<Viewing> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Viewing> findByViewingCodeIgnoreCaseAndDeletedAtIsNull(String viewingCode);

  boolean existsByViewingCodeIgnoreCaseAndDeletedAtIsNull(String viewingCode);

  Page<Viewing> findAllByDeletedAtIsNull(Pageable pageable);

  List<Viewing> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** The viewings scheduled within a window, used to build the calendar. */
  List<Viewing> findAllByScheduledAtBetweenAndDeletedAtIsNull(
      LocalDateTime startInclusive, LocalDateTime endInclusive);

  /** One agent's diary. */
  List<Viewing> findAllByAgentIdAndDeletedAtIsNull(UUID agentId);

  Page<Viewing> findAllByAgentIdAndDeletedAtIsNull(UUID agentId, Pageable pageable);

  /** The viewings of one property. */
  List<Viewing> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  long countByStatusAndDeletedAtIsNull(ViewingStatus status);

  /**
   * The viewings still ahead of the reader, oldest first - the dashboard rail. The caller passes the
   * open statuses ({@code SCHEDULED}, {@code CONFIRMED}), mirroring the schema's partial index over
   * exactly those rows.
   */
  @Query(
      "select v from Viewing v "
          + "where v.deletedAt is null and v.scheduledAt > :after and v.status in :openStatuses "
          + "order by v.scheduledAt asc")
  List<Viewing> findUpcomingAfter(
      @Param("after") LocalDateTime after, @Param("openStatuses") Collection<ViewingStatus> openStatuses);

  /**
   * The diary, with the associations a row reads eager-fetched so it does not incur a query per row:
   * the property, the unit and the agent.
   */
  @Query(
      "select v from Viewing v "
          + "left join fetch v.property "
          + "left join fetch v.unit "
          + "left join fetch v.agent "
          + "where v.deletedAt is null")
  List<Viewing> findAllForDiary();
}
