package com.meridian.realestate.repository;

import com.meridian.realestate.domain.LeadFollowup;
import com.meridian.realestate.domain.LeadFollowupStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** The follow-up diary. */
public interface LeadFollowupRepository extends JpaRepository<LeadFollowup, UUID> {

  /** The follow-ups planned for one lead, soonest first. */
  List<LeadFollowup> findAllByLeadIdAndDeletedAtIsNullOrderByDueAtAsc(UUID leadId);

  /** The same diary, paged; the caller supplies the validated sort. */
  Page<LeadFollowup> findAllByLeadIdAndDeletedAtIsNull(UUID leadId, Pageable pageable);

  /**
   * The follow-ups that have come due before the given moment and are still in the given state -
   * typically {@code PENDING}, which is what the "needs attention" rail wants.
   */
  List<LeadFollowup> findAllByStatusAndDueAtBeforeAndDeletedAtIsNull(
      LeadFollowupStatus status, LocalDateTime before);
}
