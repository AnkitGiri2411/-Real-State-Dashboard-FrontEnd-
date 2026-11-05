package com.meridian.realestate.repository;

import com.meridian.realestate.domain.LeaseStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The lease status trail.
 *
 * <p>There is no soft-delete filter here because {@code lease_status_history} has no
 * {@code deleted_at}: an append-only trail is never archived.</p>
 */
public interface LeaseStatusHistoryRepository extends JpaRepository<LeaseStatusHistory, UUID> {

  /** A lease's transitions, newest first - the order the detail screen reads them in. */
  List<LeaseStatusHistory> findAllByLease_IdOrderByChangedAtDesc(UUID leaseId);

  List<LeaseStatusHistory> findAllByLease_Id(UUID leaseId);

  long countByLease_Id(UUID leaseId);
}
