package com.meridian.realestate.repository;

import com.meridian.realestate.domain.SaleStageHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The sale stage trail.
 *
 * <p>There is no soft-delete filter here because {@code sale_stage_history} has no
 * {@code deleted_at}: an append-only trail is never archived.</p>
 */
public interface SaleStageHistoryRepository extends JpaRepository<SaleStageHistory, UUID> {

  /** A sale's transitions, newest first - the order the detail screen reads them in. */
  List<SaleStageHistory> findAllBySale_IdOrderByChangedAtDesc(UUID saleId);

  List<SaleStageHistory> findAllBySale_Id(UUID saleId);

  long countBySale_Id(UUID saleId);
}
