package com.meridian.realestate.repository;

import com.meridian.realestate.domain.SaleStage;
import com.meridian.realestate.domain.SalesOpportunity;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * The sales pipeline.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} the caller has already validated.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A sale is archived rather than removed,
 * because the offers, stage trail and payments that reference it must stay coherent.</p>
 *
 * <p><b>The concluded set.</b> {@code ck_sales_opportunities_stage} allows {@code LEAD}, {@code
 * OFFER}, {@code NEGOTIATION}, {@code UNDER_CONTRACT}, {@code CLOSED} and {@code LOST}. The pipeline
 * index and every pipeline figure exclude the last two, so the "still live" methods here take that
 * pair from the caller - see {@link SaleStage#isConcluded()} - while the value and funnel queries
 * name them inline because their meaning has to be visible in the query itself. Either way the rule
 * is the same: a concluded deal is not pipeline, and counting it would double-count it against the
 * sale it became.</p>
 */
public interface SalesOpportunityRepository extends JpaRepository<SalesOpportunity, UUID> {

  Optional<SalesOpportunity> findByIdAndDeletedAtIsNull(UUID id);

  Optional<SalesOpportunity> findBySaleCodeIgnoreCaseAndDeletedAtIsNull(String saleCode);

  boolean existsBySaleCodeIgnoreCaseAndDeletedAtIsNull(String saleCode);

  Page<SalesOpportunity> findAllByDeletedAtIsNull(Pageable pageable);

  List<SalesOpportunity> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  List<SalesOpportunity> findAllByStageAndDeletedAtIsNull(SaleStage stage);

  List<SalesOpportunity> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  List<SalesOpportunity> findAllByBuyerIdAndDeletedAtIsNull(UUID buyerId);

  List<SalesOpportunity> findAllByOwnerAgentIdAndDeletedAtIsNull(UUID ownerAgentId);

  /** The deals still being worked - the pipeline board. */
  List<SalesOpportunity> findAllByStageNotInAndDeletedAtIsNull(
      Collection<SaleStage> concludedStages);

  /** The same set, paged, for the pipeline table. */
  Page<SalesOpportunity> findAllByStageNotInAndDeletedAtIsNull(
      Collection<SaleStage> concludedStages, Pageable pageable);

  long countByStageNotInAndDeletedAtIsNull(Collection<SaleStage> concludedStages);

  /** Live deals owned by one agent. */
  List<SalesOpportunity> findAllByOwnerAgentIdAndStageNotInAndDeletedAtIsNull(
      UUID ownerAgentId, Collection<SaleStage> concludedStages);

  /**
   * The pipeline value: the expected price summed over the deals still being worked.
   *
   * <p>Summed over {@code expected_price}, not {@code asking_price} and not {@code agreed_price} -
   * the first is what was wanted, the third only exists on a deal that has already concluded and so
   * is no longer pipeline at all. The concluded pair is written into the query so a reader can see
   * which stages are excluded without following a constant.</p>
   */
  @Query(
      "select coalesce(sum(s.expectedPrice), 0) from SalesOpportunity s "
          + "where s.deletedAt is null "
          + "and s.stage not in (com.meridian.realestate.domain.SaleStage.CLOSED, "
          + "com.meridian.realestate.domain.SaleStage.LOST)")
  BigDecimal sumOpenPipelineValue();

  /** The funnel: how many sales sit in each stage. */
  @Query(
      "select s.stage as stage, count(s) as total from SalesOpportunity s "
          + "where s.deletedAt is null group by s.stage")
  List<StageCount> countGroupedByStage();

  /**
   * The pipeline, with the associations the board reads eager-fetched so it does not incur a query
   * per row: the property, the unit, the buyer and the agent who owns the deal.
   */
  @Query(
      "select s from SalesOpportunity s "
          + "left join fetch s.property "
          + "left join fetch s.unit "
          + "left join fetch s.buyer "
          + "left join fetch s.ownerAgent "
          + "where s.deletedAt is null")
  List<SalesOpportunity> findAllForPipeline();

  /** One rung of the funnel: a stage and how many live sales are in it. */
  interface StageCount {

    SaleStage getStage();

    long getTotal();
  }
}
