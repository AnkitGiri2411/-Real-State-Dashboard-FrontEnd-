package com.meridian.realestate.repository;

import com.meridian.realestate.domain.AuditLog;
import com.meridian.realestate.domain.AuditSeverity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The audit trail.
 *
 * <p>{@link JpaSpecificationExecutor} is part of the contract rather than a convenience. The audit
 * screen is the one place in the product where the user combines filters freely - a module and an
 * actor and a date window and a severity, then a property on top - and enumerating those
 * combinations as derived queries would produce a method per permutation, most of them never called
 * and none of them reviewable. One specification composed at the call site keeps the filter
 * language in one place.</p>
 *
 * <p>There is no soft delete and no update here. The trail is append-only, so "archived entries"
 * is not a state this table has: an entry is written when the event happens and is never rewritten
 * or removed.</p>
 */
public interface AuditLogRepository
    extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

  /**
   * The audit screen's page, with the acting account loaded on every row.
   *
   * <p>The order is not written into the query. It arrives as the caller's {@link Pageable},
   * validated by {@code PageRequestFactory} against the fields this list offers, so asking for a
   * different column actually changes the order instead of being silently overridden - and recency,
   * which is what the trail is normally read by, is simply the caller's default sort.</p>
   *
   * <p>The actor is fetch-joined because the table names "who" on every row; without the join a
   * twenty-row page is twenty-one queries. The count query is written out because the tally has no
   * use for the actor and a fetch join there would be work done for nothing.</p>
   */
  @Query(
      value = "select a from AuditLog a left join fetch a.actor",
      countQuery = "select count(a) from AuditLog a")
  Page<AuditLog> findAllWithActor(Pageable pageable);

  /**
   * Everything recorded since an instant, newest first, with the actor loaded.
   *
   * <p>This is the dashboard rail and the "what changed while I was out" call: a bounded window
   * rather than a page, because the question is about a period of time rather than a position in a
   * list. The order is part of the query here precisely because nothing is paged - a bounded read
   * with no order would answer a different question each time it ran.</p>
   */
  @Query(
      "select a from AuditLog a "
          + "left join fetch a.actor "
          + "where a.createdAt >= :since "
          + "order by a.createdAt desc")
  List<AuditLog> findSinceWithActor(@Param("since") LocalDateTime since);

  /** How many entries one module holds - the count behind the audit screen's filter chips. */
  long countByModule(String module);

  /** How many entries carry one severity, for the attention rail. */
  long countBySeverity(AuditSeverity severity);

  /**
   * The per-module tally, busiest module first.
   *
   * <p>Grouped in the database rather than by loading the trail and counting in memory: the table
   * grows with every action anybody takes, and the dashboard asks this question on every load.</p>
   */
  @Query(
      "select a.module as module, count(a) as total from AuditLog a "
          + "group by a.module "
          + "order by count(a) desc")
  List<ModuleCount> countGroupedByModule();

  /** One bar of the per-module tally. */
  interface ModuleCount {

    String getModule();

    long getTotal();
  }
}
