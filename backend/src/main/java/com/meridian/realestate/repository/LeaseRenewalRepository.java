package com.meridian.realestate.repository;

import com.meridian.realestate.domain.LeaseRenewal;
import com.meridian.realestate.domain.LeaseRenewalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The renewal conversations.
 *
 * <p>Soft delete applies, so every read filters on {@code deleted_at is null}. No paged query
 * carries a literal {@code order by}; sorting arrives as a {@link Pageable}.</p>
 */
public interface LeaseRenewalRepository extends JpaRepository<LeaseRenewal, UUID> {

  Optional<LeaseRenewal> findByIdAndDeletedAtIsNull(UUID id);

  /** Every renewal conversation held for a lease. */
  List<LeaseRenewal> findAllByLease_IdAndDeletedAtIsNull(UUID leaseId);

  /** Renewals in one state - the open conversations, or the accepted ones. */
  Page<LeaseRenewal> findAllByStatusAndDeletedAtIsNull(LeaseRenewalStatus status, Pageable pageable);

  long countByStatusAndDeletedAtIsNull(LeaseRenewalStatus status);
}
