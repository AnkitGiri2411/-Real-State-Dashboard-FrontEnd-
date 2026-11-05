package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.LeadStage;
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
 * The enquiry pipeline.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} the caller has already validated, so the requested order is the one applied. The
 * only exceptions are the two aggregate projections, which cannot be paged.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A lead is archived rather than removed.</p>
 */
public interface LeadRepository extends JpaRepository<Lead, UUID> {

  Optional<Lead> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Lead> findByLeadCodeIgnoreCaseAndDeletedAtIsNull(String leadCode);

  boolean existsByLeadCodeIgnoreCaseAndDeletedAtIsNull(String leadCode);

  Page<Lead> findAllByDeletedAtIsNull(Pageable pageable);

  List<Lead> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** The leads a given agent currently holds. */
  List<Lead> findAllByOwnerAgentIdAndDeletedAtIsNull(UUID ownerAgentId);

  Page<Lead> findAllByOwnerAgentIdAndDeletedAtIsNull(UUID ownerAgentId, Pageable pageable);

  /**
   * Leads whose next action is due before the given moment - the "needs attention" rail. The date is
   * the lead's own {@code next_action_at}.
   */
  List<Lead> findAllByNextActionAtBeforeAndDeletedAtIsNull(LocalDateTime before);

  /**
   * The pipeline funnel: how many leads sit in each stage. Each element is a {@code [LeadStage,
   * Long]} pair.
   */
  @Query("select l.stage, count(l) from Lead l where l.deletedAt is null group by l.stage")
  List<Object[]> countGroupedByStage();

  /**
   * How many leads carry each priority. Each element is a {@code [LeadPriority, Long]} pair.
   */
  @Query("select l.priority, count(l) from Lead l where l.deletedAt is null group by l.priority")
  List<Object[]> countGroupedByPriority();

  /**
   * The pipeline value: the expected value summed over the leads still being worked, excluding the
   * closed stages the caller passes in ({@code WON}, {@code LOST}). Never the budget maximum, which
   * is a wish rather than a forecast.
   */
  @Query(
      "select coalesce(sum(l.expectedValue), 0) from Lead l "
          + "where l.deletedAt is null and l.stage not in :closedStages")
  BigDecimal sumExpectedValueInOpenStages(@Param("closedStages") Collection<LeadStage> closedStages);

  /**
   * The register, with the associations the list actually reads eager-fetched so it does not incur a
   * query per row: the source and the owner agent.
   */
  @Query(
      "select l from Lead l "
          + "left join fetch l.source "
          + "left join fetch l.ownerAgent "
          + "where l.deletedAt is null")
  List<Lead> findAllForRegister();
}
