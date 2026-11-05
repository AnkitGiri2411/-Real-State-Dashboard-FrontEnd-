package com.meridian.realestate.repository;

import com.meridian.realestate.domain.MaintenanceStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The maintenance status trail.
 *
 * <p>There is no soft-delete filter here because {@code maintenance_status_history} has no
 * {@code deleted_at}: an append-only trail is never archived.</p>
 */
public interface MaintenanceStatusHistoryRepository
    extends JpaRepository<MaintenanceStatusHistory, UUID> {

  /** A request's transitions, newest first - the order the detail screen reads them in. */
  List<MaintenanceStatusHistory> findAllByRequest_IdOrderByChangedAtDesc(UUID requestId);

  List<MaintenanceStatusHistory> findAllByRequest_Id(UUID requestId);

  long countByRequest_Id(UUID requestId);
}
